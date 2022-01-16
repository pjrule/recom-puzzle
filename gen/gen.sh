#!/bin/bash

# NOTE: as of 2021-01-16, the HEAD version of `enumpart` is broken
# (it seems to ignore population constraints). I used the 2020-02-18
# version of `redist` (commit 08457f4a9c549f1f5fcf0da801436b081396c63e)
# to generate enumerations.

enumpart=/Users/pjrule/Dropbox/MGGG/imai/redist/inst/enumpart/enumpart
sample_size=100000
out_path=../resources/public/enum

for n in $(seq 4 8);
do
    grid_size="${n}x${n}"
    grid_out=$out_path/$grid_size
    mkdir $grid_out
    echo $grid_size

    if [ $n -gt 5 ]
    then
        sample_arg="-sample $sample_size"
        lines_per_file=1000
    else
        sample_arg="-allsols"
        # keep small enumerations together
        # (4x4 -> 4 has 117, 5x5 -> 5 has 4,006)
        lines_per_file=10000
    fi

    $enumpart "${grid_size}.dat" \
        -k $n -ratio 1.0 -comp $sample_arg  | \
    python -c $'import sys\nfor line in sys.stdin: print("".join(str(int(a) + 1) for a in line.split()))' | \
    gsplit -d -l $lines_per_file --additional-suffix=.dat - "${grid_out}/${grid_size}_"
done
