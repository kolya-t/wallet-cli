package org.tron.core.db;

import java.io.IOException;
import java.util.Map.Entry;
import lombok.extern.slf4j.Slf4j;
import org.iq80.leveldb.DBIterator;

@Slf4j
public final class StoreIterator implements org.tron.core.db.DBIterator {

  private DBIterator dbIterator;
  private boolean first = true;

  public StoreIterator(DBIterator dbIterator) {
    this.dbIterator = dbIterator;
  }

  @Override
  public void close() throws IOException {
    dbIterator.close();
  }

  @Override
  public boolean hasNext() {
    boolean hasNext = false;
    // true is first item
    try {
      if (first) {
        dbIterator.seekToFirst();
        first = false;
      }

      if (!(hasNext = dbIterator.hasNext())) { // false is last item
        dbIterator.close();
      }
    } catch (Exception e) {
      log.debug(e.getMessage(), e);
      try {
        dbIterator.close();
      } catch (IOException e1) {
        log.debug(e1.getMessage(), e1);
      }
    }

    return hasNext;
  }

  @Override
  public Entry<byte[], byte[]> next() {
    return dbIterator.next();
  }

  @Override
  public void remove() {
    throw new UnsupportedOperationException();
  }
}
