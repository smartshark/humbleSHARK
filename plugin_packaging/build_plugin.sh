#!/bin/bash

TARGET=tmp/humbleSHARK_plugin
current=`pwd`
mkdir -p $TARGET
cp -R ../build $TARGET/

cp * $TARGET/
cd $TARGET

tar -cvf "$current/humbleSHARK_plugin.tar" --exclude=*.tar --exclude=build_plugin.sh --exclude=build_plugin-src.sh --exclude=install-src.sh *
