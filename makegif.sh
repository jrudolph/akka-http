#!/bin/bash

set -ex

rm -f snaps/*png
find snaps -name '*dot' | sort | xargs -n1 -P8 -t dot -Tpng -Gsize=1920x1080 -Gratio=0.5625 -Elen=2 -Nshape=box -O
ffmpeg -framerate 2 -i 'snaps/snap_%04d.dot.png' -y -c:v libx264 -pix_fmt yuv420p -vf scale=1920x1080 out.mp4
mplayer out.mp4 -loop 0