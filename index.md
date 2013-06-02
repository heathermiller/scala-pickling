# Artifact Review: Paper 182, Object-Oriented Picklers
_Authors: Heather Miller, Philipp Haller, Eugene Burmako, Martin Oderksy_

Our paper presented (a) a model of "object-oriented picklers" and (b) a framework for generating them. This guide describes how to get started with (b), the framework for generating the object-oriented pickler combinators.

Scala-pickling aims to be very easy to use and to require little to no boilerplate at all. For pickling, the basic usage is:

    import scala.pickling._
    import json._

    val pckl = List(1, 2, 3, 4).pickle

Unpickling is just as simple, its basic usage is:

    val lst = pckl.unpickle[List[Int]]

## Getting Started Guide

With the exception of Java 1.6, this archive contains all that you will need to experiment with the scala-pickling project. To experiment with the scala-pickling project, you have a few choices:

1. **Our Test Suite**, run (or tweak) the >90 tests in our test suite.
2. **Our Benchmark Scripts**, reproduce our benchmark results locally on your architecture.
3. **Scala-pickling's bootstrapped `scalac`**, write your own independent Scala programs and run them with the scala-pickling build of `scalac` (this is optional)

Below, we show how to get started with each of the thee possibilities above.

**Choice 1 & 2 (running the test suite and benchmarks) are done through SBT. We first show how to start and use SBT below.**

### Starting SBT

Our test suite can be run using SBT (a Scala build tool). SBT is included in this distribution (at `bin/sbt`). To start SBT simply do:

    bin/sbt

SBT will then print the status of fetching its (locally-included) dependencies. After that, you'll see a `>` prompt. That's it, SBT is now started!

#### Starting the REPL from SBT

One can also interact with scala-pickling from SBT by simply starting the REPL. Once SBT is started (as per the instructions in the section above), simply do:

    > console

SBT will then compile scala-pickling and start the REPL. After compilation, you should see the following prompt:

    [info] Starting scala interpreter...
    [info]
    Welcome to Scala version 2.11.0-20130521-221431-39227d0382 (Java HotSpot(TM) 64-Bit Server VM, Java 1.6.0_43).
    Type in expressions to have them evaluated.
    Type :help for more information.

    scala>


### #1 Our Test Suite

To run the tests, once SBT is started, simply do:

    > test

SBT will compile scala-pickling as well as all of the test files, and will print output representing the status (pass/fail) all of its tests.


### #2 Our Benchmarks

To run the benchmark shown in the paper, once SBT is started, simply do:

    > travInt

This shows the results of scala-pickling for the benchmark described in section 7 (Experimental Evaluation) of the paper. This is only the "time" benchmark (Figure 1a), not the memory or size benchmarks shown in Figure 1b or 1c. The values printed to the screen are milliseconds.

_Note that SBT will set the following flags for you (as we have done for our benchmarks, as described in section 7 of the paper):_ `-Xms1536M - Xmx4096M -Xss2M -XX:MaxPermSize=512M -XX:+UseParallelGC`

We also include two additional benchmarks not shown in the paper, but published on our project page: [Scala Pickling: Benchmarks](http://lampwww.epfl.ch/~hmiller/pickling/benchmarks/) (We do have google analytics on our project website, but the project is public and linked to on github and other sites.)

To run the

### #3 Scala-pickling's `scalac`

## Step-by-Step Instructions

To experiment with the scala-pickling project, you have a few choices:

1. **Our Test Suite**, tweak existing tests or add new tests to the >90 tests in our test suite.
2. **scala-pickling's bootstrapped `scalac`**, write your own independent Scala programs and run them with the scala-pickling build of `scalac` (this is optional)

(We also provide the scripts to reproduce the benchmarks in our paper. You may change those and re-run them if you'd like as well.)

We provide suggestions below for




## Known Limitations

At the time of writing this guide, the scala-pickling project has the following known limitations:

- At this time, `Double`s are not supported by our **binary** pickle format. However, all other numerics (`Float`, `Int`, `Long`, etc) are. (Note that all primitives are supported by the JSON pickle format, however.)
- It is possible to switch between runtime compilation and runtime interpretation (as the runtime fallback mechanism). Although at present, the switch is manual- to toggle between the two, one must change lines 121 & 139 in source file [core/src/main/scala/pickling/package.scala](core/src/main/scala/pickling/package.scala).

