#!/bin/bash

# NOTE: as of 2021-01-16, the HEAD version of `enumpart` is broken
# (it seems to ignore population constraints). I used the 2020-02-18
# version of `redist` (commit 08457f4a9c549f1f5fcf0da801436b081396c63e)
# to generate enumerations.
enumpart=/Users/pjrule/Dropbox/MGGG/imai/redist/inst/enumpart/enumpart

for width in $(seq 4 8);
do
    for height in $(seq $width 9);
    do
        echo "--- ${width}x${height} -> ${width}"
        $enumpart "${width}x${height}.dat" -k $width -ratio 1.0 2>&1 >/dev/null  | grep "#node"
        if [ $width -ne $height ]; then
            echo "--- ${width}x${height} -> ${height}"
            $enumpart "${width}x${height}.dat" -k $height -ratio 1.0 2>&1 >/dev/null  | grep "#node"
        fi
    done
done
