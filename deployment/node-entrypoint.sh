#!/bin/sh
set -eu

# Course PDFs moved to an external read-only source after some existing
# node-pdfs volumes had already received the historical .seeded marker. Keep a
# separate marker so those volumes get the reviewed baseline once without
# overwriting user uploads or later operator-managed files.
if [ ! -e /app/pdfs/.course-pdfs-seeded ] && [ -d /app/default-pdfs ]; then
  cp -R -n /app/default-pdfs/. /app/pdfs/
  touch /app/pdfs/.course-pdfs-seeded
fi

# Preserve the legacy marker for existing Node-volume semantics. It is not
# used to decide whether the external course-PDF source still needs its first
# import.
if [ ! -e /app/pdfs/.seeded ]; then
  touch /app/pdfs/.seeded
fi

exec "$@"
