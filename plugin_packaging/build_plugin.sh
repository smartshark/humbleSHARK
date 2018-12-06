#!/bin/bash

TARGET=tmp/humbleSHARK_plugin
current=`pwd`
mkdir -p $TARGET
mkdir -p $TARGET/build/
mkdir -p $TARGET/workspace/humbleSHARK/
cp -R ../src $TARGET/workspace/humbleSHARK/

mkdir -p $TARGET/workspace/commonSHARK/
cp -R ../src $TARGET/workspace/commonSHARK/
cp -R ../../commonSHARK/lib $TARGET/workspace/commonSHARK/

cp ../build-target.xml $TARGET/
cp * $TARGET/
cd $TARGET

tar -cvf "$current/humbleSHARK_plugin.tar" --exclude=*.tar --exclude=build_plugin.sh *
