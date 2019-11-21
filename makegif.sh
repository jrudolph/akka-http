#!/bin/bash

set -ex

rm snaps/*png
find snaps -name '*dot' | sort | xargs -n1 -P8 -t neato -Tpng -O
ffmpeg -framerate 2 -i 'snaps/snap_%04d.dot.png' -c:v libx264 -pix_fmt yuv420p -vf scale=2300x2300 out.mp4