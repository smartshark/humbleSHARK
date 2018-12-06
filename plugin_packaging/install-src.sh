#!/bin/sh
PLUGIN_PATH=$1

cd $PLUGIN_PATH

# Build jar file or perform other tasks
ant -f build-target.xml
