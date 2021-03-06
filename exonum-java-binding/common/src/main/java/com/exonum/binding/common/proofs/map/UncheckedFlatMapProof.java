/*
 * Copyright 2018 The Exonum Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.exonum.binding.common.proofs.map;

import static com.exonum.binding.common.hash.Funnels.hashCodeFunnel;
import static com.exonum.binding.common.proofs.DbKeyCompressedFunnel.dbKeyCompressedFunnel;
import static com.exonum.binding.common.proofs.DbKeyFunnel.dbKeyFunnel;
import static java.util.Collections.emptySet;
import static java.util.stream.Collectors.toList;

import com.exonum.binding.common.collect.MapEntry;
import com.exonum.binding.common.hash.HashCode;
import com.exonum.binding.common.hash.HashFunction;
import com.exonum.binding.common.hash.Hashing;
import com.exonum.binding.common.proofs.map.DbKey.Type;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.ByteString;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * An unchecked flat map proof, which does not include any intermediate nodes.
 */
public class UncheckedFlatMapProof implements UncheckedMapProof {

  private static final HashFunction HASH_FUNCTION = Hashing.defaultHashFunction();

  private final List<MapProofEntry> proof;

  private final List<MapEntry<ByteString, ByteString>> entries;

  private final List<ByteString> missingKeys;

  @VisibleForTesting
  static final byte BLOB_PREFIX = 0x00;
  @VisibleForTesting
  static final byte MAP_ROOT_PREFIX = 0x03;
  @VisibleForTesting
  static final byte MAP_NODE_PREFIX = 0x04;

  UncheckedFlatMapProof(
      List<MapProofEntry> proof,
      List<MapEntry<ByteString, ByteString>> entries,
      List<byte[]> missingKeys) {
    this.proof = proof;
    this.entries = entries;
    this.missingKeys = missingKeys.stream()
        .map(ByteString::copyFrom)
        .collect(toList());
  }

  @Override
  public CheckedMapProof check() {
    MapProofStatus orderCheckResult = orderCheck();
    if (orderCheckResult != MapProofStatus.CORRECT) {
      return CheckedFlatMapProof.invalid(orderCheckResult);
    }
    if (containsInvalidHashes()) {
      return CheckedFlatMapProof.invalid(MapProofStatus.INVALID_HASH_SIZE);
    }
    if (prefixesIncluded()) {
      return CheckedFlatMapProof.invalid(MapProofStatus.EMBEDDED_PATH);
    }
    if (isEmptyProof()) {
      return checkEmptyProof();
    } else if (isSingletonProof()) {
      return checkSingletonProof();
    } else {
      return checkProof();
    }
  }

  /**
   * Checks that all entries in the proof are in the valid order.
   *
   * <p>The keys must be in ascending order as defined by
   * the {@linkplain DbKey#compareTo(DbKey) comparator}; there must not be duplicates.
   *
   * @return {@code MapProofStatus.CORRECT} if every following key is greater than the previous
   *         {@code MapProofStatus.INVALID_ORDER} if any following key key is lesser than the
   *         previous
   *         {@code MapProofStatus.DUPLICATE_PATH} if there are two equal keys
   *         {@code MapProofStatus.EMBEDDED_PATH} if one key is a prefix of another
   * @see DbKey#compareTo(DbKey)
   */
  private MapProofStatus orderCheck() {
    for (int i = 1; i < proof.size(); i++) {
      DbKey key = proof.get(i - 1).getDbKey();
      DbKey nextKey = proof.get(i).getDbKey();
      int comparisonResult = key.compareTo(nextKey);
      if (comparisonResult < 0) {
        if (key.isPrefixOf(nextKey)) {
          return MapProofStatus.EMBEDDED_PATH;
        }
      } else if (comparisonResult == 0) {
        return MapProofStatus.DUPLICATE_PATH;
      } else {
        return MapProofStatus.INVALID_ORDER;
      }
    }
    return MapProofStatus.CORRECT;
  }

  /**
   * Returns true if any hash in the proof has size different from 32 bytes.
   */
  private boolean containsInvalidHashes() {
    // TODO: [ECR-2410] Migrate to ProofHashes#checkSha256Hash.
    return proof.stream()
        .map(MapProofEntry::getHash)
        .map(HashCode::bits)
        .anyMatch(size -> size != Hashing.DEFAULT_HASH_SIZE_BITS);
  }

  /**
   * Check if any entry has a prefix among the paths in the proof entries. Both found and absent
   * keys are checked.
   */
  private boolean prefixesIncluded() {
    Stream<DbKey> requestedKeys =
        Stream.concat(
            entries.stream()
                .map(MapEntry::getKey),
            missingKeys.stream())
        .map(DbKey::newLeafKey);

    // TODO: proof entries are checked to be sorted at this stage, so it's possible
    //   to use binary search here
    return requestedKeys
        .anyMatch(leafEntryKey -> proof.stream()
            .map(MapProofEntry::getDbKey)
            .anyMatch(proofEntryKey ->
                proofEntryKey.isPrefixOf(leafEntryKey))
        );
  }

  private boolean isEmptyProof() {
    return proof.size() + entries.size() == 0;
  }

  private CheckedMapProof checkEmptyProof() {
    return CheckedFlatMapProof.correct(getEmptyProofIndexHash(), emptySet(), toSet(missingKeys));
  }

  private boolean isSingletonProof() {
    return proof.size() + entries.size() == 1;
  }

  private CheckedMapProof checkSingletonProof() {
    if (proof.size() == 1) {
      // There are no entries, therefore, the proof node must correspond to a leaf.
      MapProofEntry entry = proof.get(0);
      DbKey.Type nodeType = entry.getDbKey().getNodeType();
      if (nodeType == Type.BRANCH) {
        return CheckedFlatMapProof.invalid(MapProofStatus.NON_TERMINAL_NODE);
      } else {
        HashCode indexHash = getSingleEntryProofIndexHash(entry);
        return CheckedFlatMapProof.correct(indexHash, toSet(entries), toSet(missingKeys));
      }
    } else {
      // The proof consists of a single leaf with a required key
      MapEntry<ByteString, ByteString> entry = entries.get(0);
      HashCode indexHash = getSingleEntryProofIndexHash(entry);
      return CheckedFlatMapProof.correct(indexHash, toSet(entries), toSet(missingKeys));
    }
  }

  private CheckedMapProof checkProof() {
    List<MapProofEntry> proofList = mergeLeavesWithBranches();
    Deque<MapProofEntry> contour = new ArrayDeque<>();
    MapProofEntry first = proofList.get(0);
    MapProofEntry second = proofList.get(1);
    DbKey lastPrefix = first.getDbKey().commonPrefix(second.getDbKey());
    contour.push(first);
    contour.push(second);
    for (int i = 2; i < proofList.size(); i++) {
      MapProofEntry currentEntry = proofList.get(i);
      DbKey newPrefix = contour.peek().getDbKey().commonPrefix(currentEntry.getDbKey());
      while (contour.size() > 1
          && newPrefix.getNumSignificantBits() < lastPrefix.getNumSignificantBits()) {
        lastPrefix = fold(contour, lastPrefix).orElse(lastPrefix);
      }
      contour.push(currentEntry);
      lastPrefix = newPrefix;
    }
    while (contour.size() > 1) {
      lastPrefix = fold(contour, lastPrefix).orElse(lastPrefix);
    }
    HashCode indexHash = getIndexHash(contour.peek().getHash());
    return CheckedFlatMapProof.correct(indexHash, toSet(entries), toSet(missingKeys));
  }

  /**
   * Creates an initial proof tree contour, by computing hashes of leaf entries and merging them
   * with the list of proof entries.
   */
  private List<MapProofEntry> mergeLeavesWithBranches() {
    int contourSize = proof.size() + entries.size();
    assert contourSize > 1 :
        "This method computes the hashes correctly for trees with multiple nodes only";

    List<MapProofEntry> proofContour = new ArrayList<>(contourSize);

    proofContour.addAll(proof);
    entries
        .stream()
        .map(e -> new MapProofEntry(DbKey.newLeafKey(e.getKey()), getLeafEntryHash(e.getValue())))
        .forEach(proofContour::add);

    proofContour.sort(Comparator.comparing(MapProofEntry::getDbKey));

    return proofContour;
  }

  /**
   * Folds two last entries in a contour and replaces them with the folded entry.
   * Returns an updated common prefix between two last entries in the contour.
   */
  private Optional<DbKey> fold(Deque<MapProofEntry> contour, DbKey lastPrefix) {
    MapProofEntry lastEntry = contour.pop();
    MapProofEntry penultimateEntry = contour.pop();
    MapProofEntry newEntry =
        new MapProofEntry(lastPrefix, computeBranchHash(penultimateEntry, lastEntry));
    Optional<DbKey> commonPrefix;
    if (!contour.isEmpty()) {
      MapProofEntry previousEntry = contour.peek();
      commonPrefix = Optional.of(previousEntry.getDbKey().commonPrefix(lastPrefix));
    } else {
      commonPrefix = Optional.empty();
    }

    contour.push(newEntry);
    return commonPrefix;
  }

  private static HashCode getEmptyProofIndexHash() {
    HashCode merkleRoot = HashCode.fromBytes(new byte[Hashing.DEFAULT_HASH_SIZE_BYTES]);
    return getIndexHash(merkleRoot);
  }

  private static HashCode getSingleEntryProofIndexHash(MapProofEntry proofEntry) {
    HashCode merkleRoot = getSingleEntryMerkleRoot(proofEntry.getDbKey(),
        proofEntry.getHash());
    return getIndexHash(merkleRoot);
  }

  private static HashCode getSingleEntryProofIndexHash(MapEntry<ByteString, ByteString> mapEntry) {
    DbKey dbKey = DbKey.newLeafKey(mapEntry.getKey());
    HashCode valueHash = getLeafEntryHash(mapEntry.getValue());
    HashCode merkleRoot = getSingleEntryMerkleRoot(dbKey, valueHash);
    return getIndexHash(merkleRoot);
  }

  private static HashCode getIndexHash(HashCode merkleRoot) {
    return HASH_FUNCTION.newHasher()
        .putByte(MAP_ROOT_PREFIX)
        .putObject(merkleRoot, hashCodeFunnel())
        .hash();
  }

  private static HashCode getSingleEntryMerkleRoot(DbKey key, HashCode valueHash) {
    assert key.getNodeType() == Type.LEAF;
    return HASH_FUNCTION.newHasher()
        .putByte(MAP_NODE_PREFIX)
        .putObject(key, dbKeyFunnel())
        .putObject(valueHash, hashCodeFunnel())
        .hash();
  }

  private static HashCode getLeafEntryHash(ByteString entryValue) {
    return HASH_FUNCTION.newHasher()
        .putByte(BLOB_PREFIX)
        .putBytes(entryValue.toByteArray())
        .hash();
  }

  private static HashCode computeBranchHash(MapProofEntry leftChild, MapProofEntry rightChild) {
    return HASH_FUNCTION
        .newHasher()
        .putByte(MAP_NODE_PREFIX)
        .putObject(leftChild.getHash(), hashCodeFunnel())
        .putObject(rightChild.getHash(), hashCodeFunnel())
        .putObject(leftChild.getDbKey(), dbKeyCompressedFunnel())
        .putObject(rightChild.getDbKey(), dbKeyCompressedFunnel())
        .hash();
  }

  private <T> Set<T> toSet(List<T> list) {
    return ImmutableSet.copyOf(list);
  }
}
