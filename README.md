# Cuneiform Container Profiling

In this experiment, I evaluate a setup for profiling the memory (and other resource) usage of Docker containers.
It is a setup that simulates the interaction with Cuneiform within a YARN container, but leaves aside the entire distributed aspect, i.e., integration in Hi-WAY.

The setup interacts with all of Cuneiforms components. 
- cf-java is the Java abstraction that allows talking to an Erlang workflow evaluation server
- cf_lang is the Erlang server that accepts a workflow and generates Effi requests, in my setup running at dbis44
- Effi is the runner that accepts Effi requests, does something and then returns a result file, which is sent back to the server to get further instructions

In addition, the setup includes

- launching Docker containers with Effi and the 3rd party tools installed
- launching cAdvisor as a Docker container to monitor other Docker containers


I use Jörgen Brandt's cuneiform java remote workflow class to send a workflow to a

- Jörgen Brandt's Variant Call workflow [1]


### Cuneiform Component Versions
As of 2017/08/02 the matching versions are

- cf-java 0.0.3
- cf_lang 0.1.0
- Effi 0.1.3
  
### References

[1] https://github.com/joergen7/variant-call

