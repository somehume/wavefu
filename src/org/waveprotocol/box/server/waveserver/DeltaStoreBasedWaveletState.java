/**
 * Copyright 2010 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.waveprotocol.box.server.waveserver;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableFutureTask;
import com.google.gxp.compiler.io.RuntimeIOException;

import org.waveprotocol.box.common.DeltaSequence;
import org.waveprotocol.box.server.persistence.PersistenceException;
import org.waveprotocol.box.server.util.WaveletDataUtil;
import org.waveprotocol.wave.federation.Proto.ProtocolAppliedWaveletDelta;
import org.waveprotocol.wave.model.id.IdURIEncoderDecoder;
import org.waveprotocol.wave.model.id.WaveletName;
import org.waveprotocol.wave.model.operation.OperationException;
import org.waveprotocol.wave.model.operation.wave.TransformedWaveletDelta;
import org.waveprotocol.wave.model.version.HashedVersion;
import org.waveprotocol.wave.model.version.HashedVersionFactory;
import org.waveprotocol.wave.model.version.HashedVersionFactoryImpl;
import org.waveprotocol.wave.model.wave.data.ReadableWaveletData;
import org.waveprotocol.wave.model.wave.data.WaveletData;
import org.waveprotocol.wave.util.escapers.jvm.JavaUrlCodec;
import org.waveprotocol.wave.util.logging.Log;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Simplistic {@link DeltaStore}-backed wavelet state implementation
 * which goes to persistent storage for every history request.
 *
 * TODO(soren): rewire this class to be backed by {@link WaveletStore} and
 * read the snapshot from there instead of computing it in the
 * DeltaStoreBasedWaveletState constructor.
 *
 * @author soren@google.com (Soren Lassen)
 */
class DeltaStoreBasedWaveletState implements WaveletState {

  private static final Log LOG = Log.get(DeltaStoreBasedWaveletState.class);

  private static final IdURIEncoderDecoder URI_CODEC =
      new IdURIEncoderDecoder(new JavaUrlCodec());

  private static final HashedVersionFactory HASH_FACTORY =
      new HashedVersionFactoryImpl(URI_CODEC);

  private static final Function<WaveletDeltaRecord, TransformedWaveletDelta> TRANSFORMED =
      new Function<WaveletDeltaRecord, TransformedWaveletDelta>() {
        @Override
        public TransformedWaveletDelta apply(WaveletDeltaRecord record) {
          return record.getTransformedDelta();
        }
      };

  /**
   * @return An entry keyed by a hashed version with the given version number,
   *         if any, otherwise null.
   */
  private static <T> Map.Entry<HashedVersion, T> lookupCached(NavigableMap<HashedVersion, T> map,
      long version) {
    // Smallest key with version number >= version.
    HashedVersion key = HashedVersion.unsigned(version);
    Map.Entry<HashedVersion, T> entry = map.ceilingEntry(key);
    return (entry != null && entry.getKey().getVersion() == version) ? entry : null;
  }

  /**
   * Creates a new delta store based state.
   *
   * The executor must ensure that only one thread executes at any time for each
   * state instance.
   *
   * @param deltasAccess delta store accessor
   * @param persistExecutor executor for making persistence calls
   * @return a state initialized from the deltas
   * @throws PersistenceException if a failure occurs while reading or
   *         processing stored deltas
   */
  public static DeltaStoreBasedWaveletState create(DeltaStore.DeltasAccess deltasAccess,
      Executor persistExecutor) throws PersistenceException {
    if (deltasAccess.isEmpty()) {
      return new DeltaStoreBasedWaveletState(deltasAccess, ImmutableList.<WaveletDeltaRecord>of(),
          null, persistExecutor);
    } else {
      try {
        ImmutableList<WaveletDeltaRecord> deltas = readAll(deltasAccess);
        WaveletData snapshot = WaveletDataUtil.buildWaveletFromDeltas(deltasAccess.getWaveletName(),
            Iterators.transform(deltas.iterator(), TRANSFORMED));
        return new DeltaStoreBasedWaveletState(deltasAccess, deltas, snapshot, persistExecutor);
      } catch (IOException e) {
        throw new PersistenceException("Failed to read stored deltas", e);
      } catch (OperationException e) {
        throw new PersistenceException("Failed to compose stored deltas", e);
      }
    }
  }

  /**
   * Reads all deltas from persistent storage.
   */
  private static ImmutableList<WaveletDeltaRecord> readAll(WaveletDeltaRecordReader reader)
      throws IOException {
    long startVersion = 0;
    long endVersion = reader.getEndVersion().getVersion();
    return readDeltasInRange(reader, startVersion, endVersion);
  }

  private static ImmutableList<WaveletDeltaRecord> readDeltasInRange(
      final WaveletDeltaRecordReader reader, final long startVersion, final long endVersion) throws IOException {
    Preconditions.checkArgument(!reader.isEmpty());
    ImmutableList.Builder<WaveletDeltaRecord> result = ImmutableList.builder();
    long i = startVersion;
    while (i < endVersion) {
      WaveletDeltaRecord delta;
      delta = reader.getDelta(i);
      result.add(delta);
      i = delta.getResultingVersion().getVersion();
    }
    return result.build();
  }

  private final Executor persistExecutor;
  private final HashedVersion versionZero;
  private final DeltaStore.DeltasAccess deltasAccess;

  /** The lock that guards access to persistence related state. */
  private Object persistLock = new Object();

  /**
   * Indicates the version of the latest appended delta that was already requested to be
   * persisted.
   */
  private HashedVersion latestVersionToPersist = null;

  /** The persist task that will be executed next. */
  private ListenableFutureTask<Void> nextPersistTask = null;

  /**
   * Processes the persist task and checks if there is another task to do when
   * one task is done. In such a case, it writes all waiting to be persisted
   * deltas to persistent storage in one operation.
   */
  private final Callable<Void> persisterTask = new Callable<Void>() {
    @Override
    public Void call() throws PersistenceException {
      HashedVersion last;
      HashedVersion version;
      synchronized (persistLock) {
        last = lastPersistedVersion.get();
        version = latestVersionToPersist;
      }
      if (last != null && version.getVersion() <= last.getVersion()) {
        LOG.info("Attempt to persist version " + version
            + " smaller than last persisted version " + last);
        // Done, version is already persisted.
        version = last;
      } else {
        ImmutableList.Builder<WaveletDeltaRecord> deltas = ImmutableList.builder();
        HashedVersion v = (last == null) ? versionZero : last;
        do {
          WaveletDeltaRecord d =
              new WaveletDeltaRecord(v, appliedDeltas.get(v), transformedDeltas.get(v));
          deltas.add(d);
          v = d.getResultingVersion();
        } while (v.getVersion() < version.getVersion());
        Preconditions.checkState(v.equals(version));
        deltasAccess.append(deltas.build());
      }
      synchronized (persistLock) {
        Preconditions.checkState(last == lastPersistedVersion.get(),
            "lastPersistedVersion changed while we were writing to storage");
        lastPersistedVersion.set(version);
        if (nextPersistTask != null) {
          persistExecutor.execute(nextPersistTask);
          nextPersistTask = null;
        } else {
          latestVersionToPersist = null;
        }
      }
      return null;
    }
  };

  /** Keyed by appliedAtVersion. */
  private final ConcurrentNavigableMap<HashedVersion, ByteStringMessage<ProtocolAppliedWaveletDelta>> appliedDeltas =
      new ConcurrentSkipListMap<HashedVersion, ByteStringMessage<ProtocolAppliedWaveletDelta>>();

  /** Keyed by appliedAtVersion. */
  private final ConcurrentNavigableMap<HashedVersion, TransformedWaveletDelta> transformedDeltas =
      new ConcurrentSkipListMap<HashedVersion, TransformedWaveletDelta>();

  /** Is null if the wavelet state is empty. */
  private WaveletData snapshot;

  /**
   * Last version persisted with a call to persist(), or null if never called.
   * It's an atomic reference so we can set in one thread (which
   * asynchronously writes deltas to storage) and read it in another,
   * simultaneously.
   */
  private final AtomicReference<HashedVersion> lastPersistedVersion;

  /**
   * Constructs a wavelet state with the given deltas and snapshot.
   * The deltas must be the contents of deltasAccess, and they
   * must be contiguous from version zero.
   * The snapshot must be the composition of the deltas, or null if there
   * are no deltas. The constructed object takes ownership of the
   * snapshot and will mutate it if appendDelta() is called.
   */
  @VisibleForTesting
  DeltaStoreBasedWaveletState(DeltaStore.DeltasAccess deltasAccess,
      List<WaveletDeltaRecord> deltas, WaveletData snapshot, Executor persistExecutor) {
    Preconditions.checkArgument(deltasAccess.isEmpty() == deltas.isEmpty());
    Preconditions.checkArgument(deltas.isEmpty() == (snapshot == null));
    this.persistExecutor = persistExecutor;
    this.versionZero = HASH_FACTORY.createVersionZero(deltasAccess.getWaveletName());
    this.deltasAccess = deltasAccess;
    this.snapshot = snapshot;
    this.lastPersistedVersion = new AtomicReference<HashedVersion>(deltasAccess.getEndVersion());
  }

  @Override
  public WaveletName getWaveletName() {
    return deltasAccess.getWaveletName();
  }

  @Override
  public ReadableWaveletData getSnapshot() {
    return snapshot;
  }

  @Override
  public HashedVersion getCurrentVersion() {
    return (snapshot == null) ? versionZero : snapshot.getHashedVersion();
  }

  @Override
  public HashedVersion getLastPersistedVersion() {
    HashedVersion version = lastPersistedVersion.get();
    return (version == null) ? versionZero : version;
  }

  @Override
  public HashedVersion getHashedVersion(long version) {
    final Entry<HashedVersion, TransformedWaveletDelta> cachedEntry =
        lookupCached(transformedDeltas, version);
    if (version == 0) {
      return versionZero;
    } else if (snapshot == null) {
      return null;
    } else if (version == snapshot.getVersion()) {
      return snapshot.getHashedVersion();
    } else {
      WaveletDeltaRecord delta;
      try {
        delta = lookup(version);
      } catch (IOException e) {
        throw new RuntimeIOException(e);
      }
      if (delta == null && cachedEntry != null) {
        return cachedEntry.getKey();
      } else {
       return delta != null ? delta.getAppliedAtVersion() : null;
      }
    }
  }

  @Override
  public TransformedWaveletDelta getTransformedDelta(
      final HashedVersion beginVersion) {
    TransformedWaveletDelta delta = transformedDeltas.get(beginVersion);
    if (delta != null) {
      return delta;
    } else {
      WaveletDeltaRecord nowDelta;
      try {
        nowDelta = lookup(beginVersion.getVersion());
      } catch (IOException e) {
        throw new RuntimeIOException(e);
      }
      return nowDelta != null ? nowDelta.transformed : null;
    }
  }

  @Override
  public TransformedWaveletDelta getTransformedDeltaByEndVersion(final HashedVersion endVersion) {
    Preconditions.checkArgument(endVersion.getVersion() > 0, "end version %s is not positive",
        endVersion);
    final TransformedWaveletDelta cachedDelta = transformedDeltas.lowerEntry(endVersion).getValue();
    if (snapshot == null) {
      return null;
    } else {
      WaveletDeltaRecord deltaRecord = getDeltaRecordByEndVersion(endVersion);
      TransformedWaveletDelta delta;
      if (deltaRecord == null && cachedDelta.getResultingVersion().equals(endVersion)) {
        delta = cachedDelta;
      } else {
        delta = deltaRecord != null ? deltaRecord.getTransformedDelta() : null;
      }
      return delta;
    }
  }

  @Override
  public DeltaSequence getTransformedDeltaHistory(final HashedVersion startVersion,
      final HashedVersion endVersion) {
    Preconditions.checkArgument(startVersion.getVersion() < endVersion.getVersion(),
        "Start version %s should be smaller than end version %s", startVersion, endVersion);
    // The history deltas can be either in the memory - waiting to be persisted,
    // or already persisted. We take both and merge into one list.
    final NavigableMap<HashedVersion, TransformedWaveletDelta> cachedDeltas = Maps.newTreeMap();
    cachedDeltas.putAll(transformedDeltas.subMap(startVersion, true, endVersion, false));
    ImmutableList<WaveletDeltaRecord> persistedDeltas;
    try {
      persistedDeltas =
          readDeltasInRange(deltasAccess, startVersion.getVersion(), endVersion.getVersion());
    } catch (IOException e) {
      throw new RuntimeIOException(e);
    }
    NavigableMap<HashedVersion, TransformedWaveletDelta> allTransformedDeltasMap =
        Maps.newTreeMap();
    allTransformedDeltasMap.putAll(cachedDeltas);
    for (WaveletDeltaRecord d : persistedDeltas) {
      allTransformedDeltasMap.put(d.getAppliedAtVersion(), d.getTransformedDelta());
    }
    DeltaSequence nowDeltaSequence;
    if (!allTransformedDeltasMap.isEmpty()
        && allTransformedDeltasMap.firstKey().equals(startVersion)
        && allTransformedDeltasMap.lastEntry().getValue().getResultingVersion().equals(endVersion)) {
      List<TransformedWaveletDelta> cachedAndPersitentDeltasList =
          Lists.newArrayList(allTransformedDeltasMap.values());
      nowDeltaSequence = DeltaSequence.of(cachedAndPersitentDeltasList);
    } else {
      nowDeltaSequence = null;
    }
    return nowDeltaSequence;
  }

  @Override
  public ByteStringMessage<ProtocolAppliedWaveletDelta> getAppliedDelta(
      HashedVersion beginVersion) {
    ByteStringMessage<ProtocolAppliedWaveletDelta> delta = appliedDeltas.get(beginVersion);
    if (delta != null) {
      return delta;
    } else {
      WaveletDeltaRecord record = null;
      try {
        record = lookup(beginVersion.getVersion());
      } catch (IOException e) {
        new RuntimeIOException(e);
      }
      return record != null ? record.applied : null;
    }
  }

  @Override
  public ByteStringMessage<ProtocolAppliedWaveletDelta> getAppliedDeltaByEndVersion(
      final HashedVersion endVersion) {
    Preconditions.checkArgument(endVersion.getVersion() > 0,
        "end version %s is not positive", endVersion);
    final ByteStringMessage<ProtocolAppliedWaveletDelta> cachedDelta =
        appliedDeltas.lowerEntry(endVersion).getValue();
    WaveletDeltaRecord deltaRecord = getDeltaRecordByEndVersion(endVersion);
    ByteStringMessage<ProtocolAppliedWaveletDelta> appliedDelta;
    if (deltaRecord == null && isDeltaBoundary(endVersion)) {
      appliedDelta = cachedDelta;
    } else {
      appliedDelta = deltaRecord != null ? deltaRecord.getAppliedDelta() : null;
    }
    return appliedDelta;
  }

  @Override
  public Collection<ByteStringMessage<ProtocolAppliedWaveletDelta>> getAppliedDeltaHistory(
      HashedVersion startVersion, HashedVersion endVersion) {
    Preconditions.checkArgument(startVersion.getVersion() < endVersion.getVersion());
    return (isDeltaBoundary(startVersion) && isDeltaBoundary(endVersion))
        ? appliedDeltas.subMap(startVersion, endVersion).values()
        : null;
  }

  public Collection<ByteStringMessage<ProtocolAppliedWaveletDelta>> getAppliedDeltaHistory1(
      final HashedVersion startVersion, final HashedVersion endVersion) {
    Preconditions.checkArgument(startVersion.getVersion() < endVersion.getVersion());
    final Set<ByteStringMessage<ProtocolAppliedWaveletDelta>> allDeltas = Sets.newHashSet();
    allDeltas.addAll(appliedDeltas.subMap(startVersion, endVersion).values());
    ImmutableList<WaveletDeltaRecord> persistedDeltas;
    try {
      persistedDeltas =
          readDeltasInRange(deltasAccess, startVersion.getVersion(), endVersion.getVersion());
    } catch (IOException e) {
      throw new RuntimeIOException(e);
    }
    for (WaveletDeltaRecord d : persistedDeltas) {
      allDeltas.add(d.getAppliedDelta());
    }
    Collection<ByteStringMessage<ProtocolAppliedWaveletDelta>> deltaCollection =
        Lists.newArrayList();
    if (isDeltaBoundary(startVersion) && isDeltaBoundary(endVersion)) {
      for (ByteStringMessage<ProtocolAppliedWaveletDelta> appliedDelta : allDeltas) {
        deltaCollection.add(appliedDelta);
      }
    }
    return deltaCollection;
  }

  @Override
  public void appendDelta(HashedVersion appliedAtVersion,
      TransformedWaveletDelta transformedDelta,
      ByteStringMessage<ProtocolAppliedWaveletDelta> appliedDelta)
      throws OperationException {
    HashedVersion currentVersion = getCurrentVersion();
    Preconditions.checkArgument(currentVersion.equals(appliedAtVersion),
        "Applied version %s doesn't match current version %s", appliedAtVersion, currentVersion);

    if (appliedAtVersion.getVersion() == 0) {
      Preconditions.checkState(lastPersistedVersion.get() == null);
      snapshot = WaveletDataUtil.buildWaveletFromFirstDelta(getWaveletName(), transformedDelta);
    } else {
      WaveletDataUtil.applyWaveletDelta(transformedDelta, snapshot);
    }

    // Now that we built the snapshot without any exceptions, we record the delta.
    transformedDeltas.put(appliedAtVersion, transformedDelta);
    appliedDeltas.put(appliedAtVersion, appliedDelta);
  }

  @Override
  public ListenableFuture<Void> persist(final HashedVersion version) {
    Preconditions.checkArgument(version.getVersion() > 0,
        "Cannot persist non-positive version %s", version);
    Preconditions.checkArgument(isDeltaBoundary(version),
        "Version to persist %s matches no delta", version);
    synchronized (persistLock) {
      if (latestVersionToPersist != null) {
        // There's a persist task in flight.
        if (version.getVersion() <= latestVersionToPersist.getVersion()) {
          LOG.info("Attempt to persist version " + version
              + " smaller than last version requested " + latestVersionToPersist);
        } else {
          latestVersionToPersist = version;
        }
        if (nextPersistTask == null) {
          nextPersistTask = new ListenableFutureTask<Void>(persisterTask);
        }
        return nextPersistTask;
      } else {
        latestVersionToPersist = version;
        ListenableFutureTask<Void> resultTask = new ListenableFutureTask<Void>(persisterTask);
        persistExecutor.execute(resultTask);
        return resultTask;
      }
    }
  }

  @Override
  public void flush(HashedVersion version) {
    transformedDeltas.remove(transformedDeltas.lowerKey(version));
    appliedDeltas.remove(appliedDeltas.lowerKey(version));
    if (LOG.isFineLoggable()) {
      LOG.fine("Flushed deltas up to version " + version);
    }
  }

  @Override
  public void close() {
  }

  /**
   * @return An entry keyed by a hashed version with the given version number,
   *         if any, otherwise null.
   */
  private WaveletDeltaRecord lookup(long version) throws IOException {
    return deltasAccess.getDelta(version);
  }

  private WaveletDeltaRecord getDeltaRecordByEndVersion(HashedVersion endVersion) {
    long version = endVersion.getVersion();
    try {
      return deltasAccess.getDeltaByEndVersion(version);
    } catch (IOException e) {
      throw new RuntimeIOException(e);
    }
  }

  private boolean isDeltaBoundary(HashedVersion version) {
    Preconditions.checkNotNull(version, "version is null");
    return version.equals(getCurrentVersion()) || transformedDeltas.containsKey(version);
  }
}
