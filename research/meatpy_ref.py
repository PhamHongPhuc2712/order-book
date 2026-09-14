"""Reference top-of-book at 1-minute marks for one symbol, produced by MeatPy (independent ITCH 5.0 implementation).

Usage: python meatpy_ref.py <itch file (.gz or .bin)> <SYMBOL> <YYYY-MM-DD> <out.csv> [--from HH:MM --to HH:MM]

MeatPy's LOBRecorder records the book state at the first instrument update whose (microsecond) timestamp is strictly after
each requested mark, i.e. the state after every message with ts < mark + 1 us. MeatPyExport (Java) uses the same rule.
Output columns: Timestamp, Type (Bid/Ask), Level, Price, Volume, N Orders — one row per side per mark.
"""
import datetime
import sys
import time

from meatpy.event_handlers.lob_recorder import LOBRecorder
from meatpy.itch50 import ITCH50MarketProcessor, ITCH50MessageReader
from meatpy.lob import ExecutionPriorityExceptionList
from meatpy.timestamp import Timestamp
from meatpy.writers.csv_writer import CSVWriter


def main(argv):
    path, symbol, day, out = argv[:4]
    t_from, t_to = "09:30", "16:00"
    if "--from" in argv:
        t_from = argv[argv.index("--from") + 1]
    if "--to" in argv:
        t_to = argv[argv.index("--to") + 1]
    book_date = datetime.datetime.strptime(day, "%Y-%m-%d")
    h0, m0 = map(int, t_from.split(":"))
    h1, m1 = map(int, t_to.split(":"))
    marks = []
    t = book_date + datetime.timedelta(hours=h0, minutes=m0)
    end = book_date + datetime.timedelta(hours=h1, minutes=m1)
    while t <= end:
        marks.append(Timestamp.from_datetime(t))
        t += datetime.timedelta(minutes=1)

    proc = ITCH50MarketProcessor(symbol, book_date)
    writer = CSVWriter(out)
    rec = LOBRecorder(writer=writer, max_depth=1, collapse_orders=True)
    rec.record_timestamps = marks
    rec.record_always = True
    proc.handlers.append(rec)

    t0 = time.time()
    n = 0
    unresolved = 0            # MeatPy applies an out-of-FIFO fill, forgives it if the blamed head fills at the same timestamp,
    reader = ITCH50MessageReader()  # and raises the rest out of process_message: count them, the book is already updated
    for msg in reader.read_file(path):
        try:
            proc.process_message(msg)
        except ExecutionPriorityExceptionList as e:
            unresolved += len(e.args[1])
        n += 1
        if n % 10_000_000 == 0:
            print(f"{n:,} messages, {time.time() - t0:,.0f} s, marks left {len(rec.record_timestamps)}", flush=True)
    proc.processing_done()
    rec.flush_to_writer()
    writer.close()
    print(f"done: {n:,} messages in {time.time() - t0:,.0f} s; marks not reached: {len(rec.record_timestamps)}; "
          f"unresolved priority exceptions (MeatPy's own check): {unresolved}", flush=True)


if __name__ == "__main__":
    main(sys.argv[1:])
