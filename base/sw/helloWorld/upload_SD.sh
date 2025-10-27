#!/bin/bash

echo "#####################################"
echo "## Uploading Hello World to SD card"
echo "#####################################"

BUILD_DIR=./build
FILE_NAME="hello.bin"

# Check if the build file exists
if [ ! -f "$BUILD_DIR/$FILE_NAME" ]; then
    echo "Error: Build file $BUILD_DIR/$FILE_NAME does not exist."
    exit 1
fi

# input sdX1
if [ -z "$1" ]; then
    echo "Usage: $0 sdX1"
    exit 1
fi
DEVICE="/dev/"$1

# Check if the device exists
if [ ! -b "$DEVICE" ]; then
    echo "Error: Device $DEVICE does not exist."
    exit 1
fi
# Print the device name
echo " Uploading '$BUILD_DIR/$FILE_NAME' to '$DEVICE' ..."

sudo dd if="$BUILD_DIR/$FILE_NAME" of="$DEVICE" conv=fsync bs=4096 status=progress
if [ $? -ne 0 ]; then
    echo "Error: Failed to upload the file to the device."
    exit 1
fi
echo "Upload completed successfully."
