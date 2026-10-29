"""Word list from the titles of the German Wikipedia (dewiki-latest-all-titles-in-ns0.gz, CC BY-SA):
technical terms, names and compounds that general dictionaries miss.
usage: python -I tools/build_wiki_words.py <titles.gz> <out.txt>   (one word per line, with count)"""
import collections
import gzip
import re
import sys

WORD = re.compile(r"[A-Za-zÄÖÜäöüß]{3,}")
counts = collections.Counter()
with gzip.open(sys.argv[1], 'rt', encoding='utf-8', errors='replace') as f:
    next(f)  # header
    for title in f:
        title = re.sub(r'_\(.*?\)', '', title.strip())  # "Kondensator_(Elektrotechnik)" -> "Kondensator"
        for w in WORD.findall(title.replace('_', ' ')):
            counts[w] += 1
with open(sys.argv[2], 'w', encoding='utf-8') as out:
    for w, n in counts.most_common():
        out.write(f'{w}\t{n}\n')
print(len(counts), 'words')
