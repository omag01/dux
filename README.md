# dux

How to build:
Dux uses Google's Bazel as its build system. To build from source, run:
> bazel build //:dux
from the top-level directory. The resulting output is in the /output directory.
To run the program, run ./output/dux [options]