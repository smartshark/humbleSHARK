#!/bin/sh
PLUGIN_PATH=$1

cd $PLUGIN_PATH/humbleSHARK

# Build jar file or perform other tasks
./gradlew shadowJar

cp build/libs/humbleSHARK*.jar ../build/humbleSHARK.jar