#!/bin/bash

TARGET=tmp/humbleSHARK_plugin
current=`pwd`
mkdir -p $TARGET
mkdir -p $TARGET/build/
mkdir -p $TARGET/humbleSHARK/
cp -R ../src $TARGET/humbleSHARK/
cp -R ../lib $TARGET/humbleSHARK/
cp -R ../gradle* $TARGET/humbleSHARK/
cp ../build.gradle $TARGET/humbleSHARK/
cp ../settings.gradle $TARGET/humbleSHARK/

mkdir -p $TARGET/commonSHARK/
cp -R ../../commonSHARK/src $TARGET/commonSHARK/
cp -R ../../commonSHARK/lib $TARGET/commonSHARK/
cp ../../commonSHARK/build.gradle $TARGET/commonSHARK/

cp * $TARGET/
cd $TARGET

tar -cvf "$current/humbleSHARK_plugin.tar" --exclude=*.tar --exclude=build_plugin.sh *
