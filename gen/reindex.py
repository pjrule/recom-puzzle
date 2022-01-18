"""Reindexes district assignments (0 -> 1) and removes spaces."""
import sys
for line in sys.stdin:
    print(''.join(str(int(a) + 1) for a in line.split()))

