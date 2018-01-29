package org.dux.stracetool;

import org.dux.cli.DuxCLI;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Tracer {
    // TODO Allow for arbitrary temp file names (need to sanitize in constructor)
    private static final String TMP_FILE = ".dux_out";
    private String[] args;

    private static final String[] STRACE_CALL = {
            "strace",
            "-o", TMP_FILE               // write to tmp file
    };

    private static final String[] PROCMON_CALL = {
            "TODO implement"
    };

    public Tracer(List<String> args) {
        String os = System.getProperty("os.name");
        List<String> argList = null;
        if (os.startsWith("Linux")) {
            argList = new ArrayList<>(Arrays.asList(STRACE_CALL));
        } else if (os.startsWith("Windows")) {
            // TODO implement
            argList = new ArrayList<>(Arrays.asList(PROCMON_CALL));
        } else {
            // TODO What to do here?
        }
        System.out.println("args = " + args);
        argList.addAll(args);
        System.out.println("args = " + argList);
        this.args = argList.toArray(new String[0]);
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
        DuxCLI.logger.debug("beginning a trace, getting runtime");
        Runtime rt = Runtime.getRuntime();
        DuxCLI.logger.debug("runtime acquired, executing program");
        System.out.println(Arrays.toString(args));
        Process proc = rt.exec(args);
        Tracer.StreamGobbler outputGobbler = new Tracer.StreamGobbler(proc.getInputStream());
        DuxCLI.logger.debug("waiting for build to terminate");
        outputGobbler.start();
        proc.waitFor();
    }
}
