# Artifact Review: Paper 182, Object-Oriented Picklers
_Authors: Heather Miller, Philipp Haller, Eugene Burmako, Martin Odersky_

Our paper presented (a) a model of "object-oriented picklers" and (b) a framework for generating them. This guide describes how to get started with (b), the framework for generating the object-oriented pickler combinators.

Scala-pickling aims to be very easy to use and to require little to no boilerplate at all. For pickling, the basic usage is:

    import scala.pickling._
    import json._
    // or import binary._ to pickle to binary format instead

    val pckl = List(1, 2, 3, 4).pickle

Unpickling is just as simple, its basic usage is:

    val lst = pckl.unpickle[List[Int]]

### API Docs
(Local) API documentation is also included and available at: [api-docs/index.html](api-docs/index.html)

## Getting Started Guide

This guide assumes you have Java 1.6 on your path. Other than that, this archive contains all that you will need to experiment with the scala-pickling project.

You will primarily be using SBT (a Scala build tool)

To experiment, you have a few ways to interact with scala-pickling:

1. **Our Test Suite**, run (or tweak) the >90 tests in our test suite.
2. **Our Benchmark Scripts**, reproduce our benchmark results locally on your architecture.
3. **Use Scala-Pickling with the Standalone Scala Compiler**, write your own independent Scala programs and run them with scala-pickling **\[this is optional\]**

Below, we show how to get started with each of the thee possibilities above.

**Choice 1 & 2 (running the test suite and benchmarks) are done through SBT. We first show how to start and use SBT below.**

### Starting SBT

Our test suite can be run using SBT (a Scala build tool). SBT is
included in this distribution (at `bin/sbt`). Before running SBT it is
necessary to adjust the absolute directory path of the local Scala
build in file [project/Build.scala](project/Build.scala). Adjust the path on line 12 to
match the directory into which you unpacked the archive. Then, to
start SBT simply do:

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

Example usage:

    scala> import scala.pickling._
    import scala.pickling._

    scala> import json._
    import json._

    scala> val p = "hi there!".pickle
    p: scala.pickling.json.JSONPickle =
    JSONPickle({
      "tpe": "java.lang.String",
      "value": "hi there!"
    })

    scala> p.unpickle[String]
    res0: String = hi there!

To quit the REPL (without quitting SBT), type `:q`

### #1 Our Test Suite

To run the tests, once SBT is started, simply do:

    > test

SBT will compile scala-pickling as well as all of the test files, and will print output representing the status (pass/fail) all of its tests.


### #2 Our Benchmarks

To run the benchmarks shown in the paper, once SBT is started, simply do:

    > travInt

This shows the results of scala-pickling for the main benchmark described in section 7 (Experimental Evaluation) of the paper. This is only the "time" benchmark (Figure 1a), not the memory or size benchmarks shown in Figure 1b or 1c.

The values printed to the screen are milliseconds. Each row represents independent JVM runs for values on the x-axis of the graph; for `travInt`, this means each row represents a different length of the `Vector` as described in the paper (from 100,000 to 1,000,000, from top to bottom). Each column corresponds to a separate run of the benchmark.

The other two benchmarks, shown in Figure 1b (free memory), and Figure 1c (size) can be run from SBT using the following commands, respectively:

    > travIntFreeMem
    > travIntSize

The values on the x-axis for `travIntFreeMem` and `travIntSize` are both in Bytes.

_Note that SBT will set the following flags for you (as we have done for our benchmarks, and have described in section 7 of the paper):_ `-Xms1536M - Xmx4096M -Xss2M -XX:MaxPermSize=512M -XX:+UseParallelGC`

We also include two additional benchmarks not shown in the paper, but published on our project page: [Scala Pickling: Benchmarks](http://lampwww.epfl.ch/~hmiller/pickling/benchmarks/) (We do have google analytics on our project website, but the project site is public, linked to on github and other sites, and gets traffic, so there is no real risk of tracing.)

Included are the following benchmarks:

- `geoTrellis`. [GeoTrellis benchmark](http://lampwww.epfl.ch/~hmiller/pickling/benchmarks/geotrellis.html) varied up to 1,000,000 elements. To run this benchmark, simply do `geoTrellis` within SBT.
- `evactor1`. [Evactor benchmark](http://lampwww.epfl.ch/~hmiller/pickling/benchmarks/evactor.html) varied up to
10,000 events. To run this benchmark, simply do `evactor1` within SBT.
- `evactor2`. [Evactor benchmark](http://lampwww.epfl.ch/~hmiller/pickling/benchmarks/evactor.html) varied up to 38,000 events. To run this benchmark, simply do `evactor2` within SBT.


### #3 Using Scala-Pickling with the Standalone Scala Compiler \[Optional\]

Also included in this archive is a standalone version of the Scala compiler which can be used with scala-pickling. It is located at `scala-local/bin/scalac`. We provide this option for those who would like to avoid the use of build tools. The use of the standalone compiler is completely optional.

**Example**, for a program `test.scala`:

    import scala.pickling._
    import binary._

    object Test extends App {
      val x = 42
      val p = x.pickle
      val up = p.unpickle[Int]
      println("unpickled: "+ up)
    }

To compile:
<br>(with a local directory `classes` for the classfiles)

    $ scala-local/bin/scalac -d classes -cp core/target/scala-2.11/classes test.scala

To run:

    $ scala-local/bin/scala -cp classes:core/target/scala-2.11/classes Test


## Step-by-Step Instructions

To experiment with the scala-pickling project, you have a few choices:

1. **Our Test Suite**, tweak existing tests or add new tests to the >90 tests in our test suite.
2. **scala-pickling's bootstrapped `scalac`**, write your own independent Scala programs and run them with the scala-pickling build of `scalac` (this is optional)

(We also provide the scripts to reproduce the benchmarks in our paper. You may change those and re-run them if you'd like as well.)

We provide suggestions below for

### #1 Adding/Tweaking Tests in the Test Suite

There are two types of tests.

- Unit Tests
- ScalaCheck Tests

Unit Tests typically . For each ScalaCheck test, .

Both types of tests are located in the directory: [core/src/test/scala/pickling](core/src/test/scala/pickling).

ScalaCheck Tests for JSON and binary formats are in the file: [core/src/test/scala/pickling/pickling-spec.scala](core/src/test/scala/pickling/pickling-spec.scala).


## Known Limitations

At the time of writing this guide, the scala-pickling project has the following known limitations:

- At this time, `Double`s are not supported by our **binary** pickle format. However, all other numerics (`Float`, `Int`, `Long`, etc) are. (Note that all primitives are supported by the JSON pickle format, however.)
- It is possible to switch between runtime compilation and runtime interpretation (as the runtime fallback mechanism). Although at present, the switch is manual- to toggle between the two, one must change lines 121 & 139 in source file [core/src/main/scala/pickling/package.scala](core/src/main/scala/pickling/package.scala).

