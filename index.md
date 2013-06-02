## Artifact Review: Paper 182, Object-Oriented Picklers
_Authors: Heather Miller, Philipp Haller, Eugene Burmako, Martin Oderksy_

Scala pickling is...



## Getting Started Guide

With the exception of Java 1.6, this archive contains all that you will need to experiment with the scala-pickling project. To experiment with the scala-pickling project, you have a few choices:

1. **Our Test Suite**, tweak existing tests or add new tests to the >90 tests in our test suite.
2. **Our Benchmark Scripts**, reproduce our benchmark results locally on your architecture.
3. **Scala-pickling's bootstrapped `scalac`**, write your own independent Scala programs and run them with the scala-pickling build of `scalac` (this is optional)

### #1 Our Test Suite

###### Starting SBT

Our test suite can be run using SBT (a Scala build tool). SBT is included in this distribution (at `bin/sbt`). To start SBT simply do:

    ./bin/sbt

SBT will then print the status of fetching its (locally-included) dependencies. After that, you'll see a `>` prompt.

To run the tests, once SBT is started, simply do:

    > test

SBT will compile scala-pickling as well as all of the test files, and will print output representing the status (pass/fail) all of its tests.

### #2 Our Benchmark Scripts

### #3 Scala-pickling's `scalac`

## Step-by-Step Instructions

To experiment with the scala-pickling project, you have a few choices:

1. **Our Test Suite**, tweak existing tests or add new tests to the >90 tests in our test suite.
2. **Our Benchmark Scripts**, reproduce our benchmark results locally on your architecture.
3. **scala-pickling's bootstrapped `scalac`**, write your own independent Scala programs and run them with the scala-pickling build of `scalac` (this is optional)