package org.dux.stracetool;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.Kernel32;

import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.File;
import java.io.FileReader;
import java.io.PrintStream;

import java.lang.reflect.Field;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

/**
 * Create with the Tracer.Builder class.
 */

public class Tracer {
    public static Logger logger;
    static {
        logger = (Logger) LoggerFactory.getLogger(Tracer.class);
        logger.setLevel(Level.INFO);
    }

    private static final String[] FILTER_ARGS = {
            "-e", "trace=open,execve,readlink,fstat,stat,lstat"
    };

    private List<String> args;

    // Windows only; already incorporated into above args list on Linux
    private String fileName;
    private boolean traceSubprocesses;
    private boolean filterCalls;

    public static class Builder {
        // Required parameters
        private final String fileName;
        private final List<String> traceCommand;

        // Optional parameters
        private boolean traceSubprocesses = false;
        private boolean filterCalls = false;

        // The output file will be a CSV regardless of what you name it.
        public Builder(String fileName, List<String> traceCommand) {
            // argument checking
            if (fileName == null || traceCommand == null) {
                throw new NullPointerException("Arguments cannot be null");
            }
            if (fileName.length() == 0) {
                throw new IllegalArgumentException("File name cannot be empty");
            }
            if (traceCommand.size() == 0) {
                throw new IllegalArgumentException("Trace command cannot be empty");
            }
            if (fileName.contains(" ")) {
                // spaces could mean they are passing arbitrary arguments to
                // strace or procmon; disallow for now
                throw new IllegalArgumentException("No spaces allowed in filename");
            }

            this.fileName = fileName;
            // don't need to sanitize trace command because users could just
            // run this command themselves without passing it through dux
            this.traceCommand = traceCommand;
        }

        public Builder traceSubprocesses() {
            traceSubprocesses = true;
            return this;
        }

        // For now, can only filter with the strace flag
        // "-e trace=open,execve,readlink,fstat,stat,lstat"
        public Builder filterCalls() {
            filterCalls = true;
            return this;
        }

        public Tracer build() {
            return new Tracer(this);
        }
    }

    private Tracer(Builder builder) {
        String os = System.getProperty("os.name");
        args = new ArrayList<>();
        if (os.startsWith("Linux")) {
            args.add("strace");
            args.add("-o");
            args.add(builder.fileName);
            if (builder.traceSubprocesses) {
                args.add("-f");
            }
            if (builder.filterCalls) {
                args.addAll(Arrays.asList(FILTER_ARGS));
            }
        } else if (os.startsWith("Windows")) {
            fileName = builder.fileName;
            traceSubprocesses = builder.traceSubprocesses;
            filterCalls = builder.filterCalls;
        } else {
            throw new UnsupportedOperationException("Unsupported OS");
        }
        args.addAll(builder.traceCommand);
    }

    // https://stackoverflow.com/questions/1732455/redirect-process-output-to-stdout
    class StreamGobbler extends Thread {
        InputStream is;

        // reads everything from is until empty.
        StreamGobbler(InputStream is) {
            this.is = is;
        }

        public void run() {
            try {
                InputStreamReader isr = new InputStreamReader(is);
                BufferedReader br = new BufferedReader(isr);
                String line=null;
                while ( (line = br.readLine()) != null)
                    System.out.println(line);
            } catch (IOException ioe) {
                ioe.printStackTrace();
            }
        }
    }

    public void trace() throws IOException, InterruptedException {
        Tracer.logger.debug("beginning a trace, getting runtime");
        Runtime rt = Runtime.getRuntime();
        Tracer.logger.debug("runtime acquired, executing program");
        String os = System.getProperty("os.name");
        if (os.startsWith("Linux")) {
            Process proc = rt.exec((String[]) args.toArray(new String[args.size()]));
            Tracer.StreamGobbler outputGobbler = new Tracer.StreamGobbler(proc.getInputStream());
            Tracer.logger.debug("waiting for build to terminate");
            outputGobbler.start();
            proc.waitFor();
        } else if (os.startsWith("Windows")) {
            // turn on Process Monitor
            Process proc1 = rt.exec("cmd /c src\\org\\dux\\stracetool\\start_trace.bat");
            proc1.waitFor();

            // run actual command to trace
            Process proc = rt.exec((String[]) args.toArray(new String[args.size()]));
            Tracer.StreamGobbler outputGobbler = new Tracer.StreamGobbler(proc.getInputStream());
            Tracer.logger.debug("waiting for build to terminate");
            outputGobbler.start();
            // TODO Replace with Java 9/10 Process.pid() once those become more mainstream
            int myPid = getPid(proc);
            proc.waitFor();

            // turn off Process Monitor
            Process proc2 = rt.exec("cmd /c src\\org\\dux\\stracetool\\end_trace.bat");
            proc2.waitFor();

            // need to manually filter after the trace if on Windows -- SLOW
            try {
                Set<Integer> parentPids = new HashSet<>();
                if (traceSubprocesses) {
                    parentPids = findParentPids(myPid, "strace.csv");
                }
                FileReader fr = new FileReader("strace.csv");
                BufferedReader br = new BufferedReader(fr);
                PrintStream out = new PrintStream(new File(fileName));
                while (br.ready()) {
                    String line = br.readLine();
                    String[] parts = skipBadLine(line);
                    if (parts == null) {
                        continue;
                    }
                    if (parentPids.contains(Integer.parseInt(parts[6])) ||
                        Integer.parseInt(parts[2]) == myPid) {
                        if (filterCalls) {
                            // NOTE: files, symbolic links, and hard links seem
                            // to all be created or read through this procedure:
                            // QueryDirectory, CreateFile, QueryBasicInformationFile,
                            // CloseFile, CreateFile, QueryStandardInformationFile,
                            // ReadFile, CloseFile

                            // So tracing any of these calls would be sufficient
                            String call = parts[3];
                            if (!(call.equals("CreateFile") || call.equals("Process Create"))) {
                                continue;
                            }
                            // TODO: what to do about readlink?
                        }
                        out.println(line);
                    }
                }
                fr.close();
                File f = new File("strace.csv");
                f.delete();
            } catch (IOException ioe) {
                // won't happen; we just created the file we are opening
                Tracer.logger.debug("Tracer.java just created file strace.csv " +
                                    "in end_trace.bat but now cannot open it...");
                ioe.printStackTrace();
            }
        } else {
            throw new UnsupportedOperationException("Unsupported OS");
        }
    }

    /**
     * Returns the given ProcMon line split on "," or null if it is a line to
     * skip (e.g. if it is a call from the Process Monitor executable itself,
     * or is the "schema" line in the Process Monitor file).
     *
     * @param line A CSV line of output from Process Monitor.
     * @return A String[] of the given Process Monitor output line split on ",".
     */
    private String[] skipBadLine(String line) {
        line = line.substring(1, line.length() - 1); // strip quotes

        // Process Monitor outputs csv
        String[] parts = line.split("\",\"");
        // e.g. "1:41:00.4573350 PM","Explorer.EXE","7572","RegQueryKey",
        // "HKCU\Software\Classes","SUCCESS","2422"

        char timestampFirstChar = parts[0].charAt(0);
        if (timestampFirstChar >= '9' || timestampFirstChar <= '0') {
            // lines should begin with a timestamp in quotes, e.g. "1:41:00.4572969 PM"
            // if not, ignore this line -- it is the schema
            return null;
        }

        if (parts[1].equalsIgnoreCase("Procmon.exe") ||
            parts[1].equalsIgnoreCase("Procmon64.exe")) {
            // ignore calls made by Process Monitor itself
            return null;
        }
        return parts;
    }

    /**
     * Returns a set of pids that could be valid parent process pids for the
     * process event data generated in ProcMon CSV log filename.
     *
     * @param startingPid The PID for which to find parent PIDs (root process).
     * @param filename The Process Monitor CSV log file name to scan for parent PIDs.
     * @return A set of valid parent PIDs for all subprocesses of the given process.
     * @throws IOException if filename is invalid.
     */
    private Set<Integer> findParentPids(int startingPid, String filename)
            throws IOException {
        // parse out pairs of pids and parent pids from filename
        Set<Integer> parentPids = new HashSet<>();
        parentPids.add(startingPid);
        List<int[]> pairs = new ArrayList<int[]>();
        FileReader fr = new FileReader(filename);
        BufferedReader br = new BufferedReader(fr);
        while (br.ready()) {
            String[] parts = skipBadLine(br.readLine());
            if (parts == null) {
                // got a bad line
                continue;
            }
            int pid = Integer.parseInt(parts[2]);
            int parent_pid = Integer.parseInt(parts[parts.length - 1]);
            int[] pair = {pid, parent_pid};
            pairs.add(pair);
        }
        fr.close();

        // find pids we need to track with fixed-point algorithm
        int start_size = 0;
        int end_size = 1;
        while (start_size != end_size) {
            start_size = parentPids.size();
            for (int[] pair : pairs) {
                if (parentPids.contains(pair[1])) {
                    parentPids.add(pair[0]);
                }
            }
            end_size = parentPids.size();
        }
        return parentPids;
    }

    /**
     * Returns the process ID of the given Process (WINDOWS-DEPENDENT!!).
     *
     * @param proc The process to find the PID of.
     * @return The PID of the given process.
     */
    private int getPid(Process proc) {
        // EXTREMELY BAD; use JNA/Reflection Windows-dependent hack to get pid
        // https://stackoverflow.com/questions/4750470/how-to-get-pid-of
        // -process-ive-just-started-within-java-program
        int pid;
        try {
            Field f = proc.getClass().getDeclaredField("handle");
            f.setAccessible(true);
            long handl = f.getLong(proc);
            Kernel32 kernel = Kernel32.INSTANCE;
            WinNT.HANDLE hand = new WinNT.HANDLE();
            hand.setPointer(Pointer.createConstant(handl));
            pid = kernel.GetProcessId(hand);
            f.setAccessible(false);
            return pid;
        } catch (IllegalAccessException iae) {
            Tracer.logger.debug("Windows pid access hack didn't work...");
        } catch (NoSuchFieldException nsfe) {
            Tracer.logger.debug("Windows pid access hack didn't work...");
        }
        return -1;
    }
}
