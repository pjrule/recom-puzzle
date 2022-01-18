#!/bin/bash
set -e

# NOTE: as of 2021-01-16, the HEAD version of `enumpart` is broken
# (it seems to ignore population constraints). I used the 2020-02-18
# version of `redist` (commit 08457f4a9c549f1f5fcf0da801436b081396c63e)
# to generate enumerations.

enumpart=/Users/pjrule/Dropbox/MGGG/imai/redist/inst/enumpart/enumpart
sample_size=100000
out_path=../resources/public/enum

for width in $(seq 4 8); do
    for height in $(seq $width 9); do
        grid_size="${width}x${height}"
        grid_out=$out_path/$grid_size
        mkdir -p "${grid_out}_${width}"
        mkdir -p "${grid_out}_${height}"
        echo $grid_size
        
        # everything ≤ 4x8, 5x6 is ≤100,000 => fully enumerate instead of sample
        if ( [ $width -eq 4 ] && [ $height -lt 9 ] ) || ( [ $width -eq 5 ] && [ $height -lt 7 ] ) ; then
            sample_arg="-allsols"
        else
            sample_arg="-sample $sample_size"
        fi

        # everything ≤ 4x7, 5x5 is ≤10,000 => one file 
        if ( [ $width -eq 4 ] && [ $height -lt 8 ] ) || ( [ $width -eq 5 ] && [ $height -lt 6 ] ) ; then
            lines_per_file=10000
        else
            lines_per_file=1000
        fi

        echo "--- ${grid_size} -> ${width} ---"
        $enumpart "${grid_size}.dat" -k $width -ratio 1.0 -comp $sample_arg | \
          python reindex.py | \
          gsplit -d -l $lines_per_file --additional-suffix=.dat - "${grid_out}_${width}/${grid_size}_${width}_"

        if [ $width -ne $height ]; then
            echo "--- ${grid_size} -> ${height} ---"
            $enumpart "${grid_size}.dat" -k $height -ratio 1.0 -comp $sample_arg | \
              python reindex.py | \
              gsplit -d -l $lines_per_file --additional-suffix=.dat - "${grid_out}_${height}/${grid_size}_${height}_"
        fi
    done
done
