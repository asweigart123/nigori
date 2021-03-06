/*
 * Copyright (C) 2011 Daniel R. Thomas (drt24)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.nigori.server;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.nigori.common.MessageLibrary;
import com.google.nigori.common.Nonce;
import com.google.nigori.common.RevValue;
import com.google.nigori.common.Util;
import com.sleepycat.je.Cursor;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.LockMode;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.Transaction;

/**
 * @author drt24
 *
 */
public class JEDatabase extends AbstractDatabase {

  private final com.sleepycat.je.Database db;
  private final Environment env;

  private static final Logger log = Logger.getLogger(JEDatabase.class.getSimpleName());
  static {
    // log.addHandler(new ConsoleHandler());
  }
  private static void severe(String message, Exception exception){
    log.log(Level.SEVERE, message, exception);
  }
  private static final DatabaseEntry USERS = new DatabaseEntry(MessageLibrary.toBytes("users"));
  private static final byte[] SEPARATOR = MessageLibrary.toBytes("/");
  private static final byte[] DATE = MessageLibrary.toBytes("date");
  private static final byte[] KEY = MessageLibrary.toBytes("key");
  private static Map<String, JEDatabase> databaseMap = new WeakHashMap<String, JEDatabase>();

  /**
   * Get an instance of a JEDatabase for a particular directory, if we already have a valid
   * JEDatabase for that directory return that instead of creating a new one
   * 
   * @param dataDirectory
   * @return a JEDatabase for that directory
   */
  public static JEDatabase getInstance(File dataDirectory) {
    String key = dataDirectory.getAbsolutePath();
    JEDatabase instance = databaseMap.get(key);
    if (instance != null) {
      try {
        instance.env.sync();
        return instance;
      } catch (DatabaseException e) {
        // No longer valid
        instance.env.close();
        databaseMap.remove(key);
      }
    }
    instance = new JEDatabase(dataDirectory);
    databaseMap.put(key, instance);
    return instance;
  }

  private JEDatabase(File dataDirectory) {
    if (!dataDirectory.exists()) {
      throw new IllegalArgumentException("Data directory must exist: " + dataDirectory);
    }
    if (!dataDirectory.isDirectory()) {
      throw new IllegalArgumentException("Data directory must be a directory: " + dataDirectory);
    }

    final EnvironmentConfig envConfig = new EnvironmentConfig();
    envConfig.setTransactional(true);
    envConfig.setAllowCreate(true);

    env = new Environment(dataDirectory, envConfig);
    final DatabaseConfig dbConfig = new DatabaseConfig();
    dbConfig.setTransactional(true);
    dbConfig.setAllowCreate(true);
    dbConfig.setSortedDuplicates(true);
    db = env.openDatabase(null, "nigori", dbConfig);
  }

  @Override
  public UserFactory getUserFactory() {
    return JUser.Factory.getInstance();
  }

  private byte[] makeBytes(byte[]... bytes){
    int length = 0;
    for (byte[] b : bytes){
      length += b.length;
    }
    byte[] answer = new byte[length];
    int index = 0;
    for (byte[] ba : bytes){
      for (byte b : ba){
        answer[index] = b;
        ++index;
      }
    }
    return answer;
  }
  private DatabaseEntry makeRegDateKey(byte[] publicHash){
    return new DatabaseEntry(makeBytes(USERS.getData(), SEPARATOR, publicHash, SEPARATOR, DATE));
  }
  private DatabaseEntry makePublicKeyKey(byte[] publicHash){
    return new DatabaseEntry(makeBytes(USERS.getData(), SEPARATOR, publicHash, SEPARATOR, KEY));
  }
  @Override
  public boolean addUser(byte[] publicKey, byte[] publicHash) {
    
    Transaction txn = null;
    try {
      txn = env.beginTransaction(null, null);
      if (haveUser(txn, publicHash)) {
        txn.commit();
        return false;// User already exists
      }

      DatabaseEntry ph = new DatabaseEntry(publicHash);
      OperationStatus res = db.put(txn, USERS, ph);
      if (res != OperationStatus.SUCCESS) {
        log.severe("Could not add ph: " + res.toString());
        txn.abort();
        return false;
      }

      DatabaseEntry regDateKey = makeRegDateKey(publicHash);
      DatabaseEntry regTime = new DatabaseEntry(Util.long2bin(System.currentTimeMillis()));
      res = db.put(txn, regDateKey, regTime);
      if (res != OperationStatus.SUCCESS) {
        log.severe("Could not add regTime: " + res.toString());
        txn.abort();
        return false;
      }

      DatabaseEntry regPublicKeyKey = makePublicKeyKey(publicHash);
      DatabaseEntry regPublicKey = new DatabaseEntry(publicKey);
      res = db.put(txn, regPublicKeyKey, regPublicKey);
      if (res != OperationStatus.SUCCESS) {
        log.severe("Could not add publicKey: " + res.toString());
        txn.abort();
        return false;
      }
      txn.commit();
      return true;
    } catch (DatabaseException e) {
      severe("Exception while adding user", e);
      try {
        if (txn != null) {
          txn.abort();
        }
      } catch (DatabaseException e1) {
        // we already had a failure, ignore this one.
      }
      return false;
    }
  }

  private boolean haveUser(Transaction txn, byte[] existingUser) {
    try {
      OperationStatus status =
          db.get(txn, makeRegDateKey(existingUser), new DatabaseEntry(), LockMode.READ_COMMITTED);
      if (status == OperationStatus.SUCCESS) {
        return true;
      } else {
        return false;
      }
    } catch (DatabaseException e) {
      severe("Exception while checking for user", e);
      return false;
    }
  }

  @Override
  public boolean haveUser(byte[] existingUser) {
    return haveUser(null, existingUser);
  }

  @Override
  public boolean deleteUser(User existingUser) {
    try {
      final Transaction txn = env.beginTransaction(null, null);
      byte[] publicHash = existingUser.getPublicHash();
      db.delete(txn, makeRegDateKey(publicHash));
      db.delete(txn, makePublicKeyKey(publicHash));
      Cursor cursor = db.openCursor(txn, null);
      try {
        OperationStatus status =
            cursor.getSearchBoth(USERS, new DatabaseEntry(publicHash), null);
        if (status == OperationStatus.SUCCESS) {
          cursor.delete();
          deleteUserData(existingUser, txn);
          return true;
        } else {
          return false;
        }
      } finally {
        cursor.close();
        txn.commit();
      }
    } catch (DatabaseException e) {
      severe("Exception while deleting user", e);
      return false;
    }
  }

  private void deleteUserData(User user, Transaction txn) {
    Cursor cursor = db.openCursor(txn, null);
    try {
      DatabaseEntry lookup = new DatabaseEntry();
      DatabaseEntry storesKey = makeStoresKey(user);
      for (OperationStatus lookupStatus = cursor.getSearchKey(storesKey, lookup, null); OperationStatus.SUCCESS == lookupStatus;
          lookupStatus = cursor.getNextDup(storesKey, lookup, null)) {
        deleteRevisions(user, lookup.getData(), txn);
        cursor.delete();
      }
    } finally {
      cursor.close();
    }
  }

  @Override
  public byte[] getPublicKey(byte[] publicHash) throws UserNotFoundException {
    try {
      DatabaseEntry regTime = new DatabaseEntry();
      OperationStatus status = db.get(null, makeRegDateKey(publicHash), regTime, LockMode.READ_COMMITTED);
      if (status == OperationStatus.SUCCESS){
        DatabaseEntry publicKey = new DatabaseEntry();
        status = db.get(null, makePublicKeyKey(publicHash), publicKey, LockMode.READ_COMMITTED);
        if (status == OperationStatus.SUCCESS) {
          return publicKey.getData();
        }
      }
      throw new UserNotFoundException();
    } catch (DatabaseException e) {
      severe("Exception while getting public key for user", e);
      throw new UserNotFoundException(e);
    }
  }

  @Override
  public User getUser(byte[] publicHash) throws UserNotFoundException {
    try {
      DatabaseEntry regTime = new DatabaseEntry();
      OperationStatus status = db.get(null, makeRegDateKey(publicHash), regTime, LockMode.READ_COMMITTED);
      if (status == OperationStatus.SUCCESS){
        DatabaseEntry publicKey = new DatabaseEntry();
        status = db.get(null, makePublicKeyKey(publicHash), publicKey, LockMode.READ_COMMITTED);
        if (status == OperationStatus.SUCCESS) {
          return new JUser(publicKey.getData(), publicHash, new Date(Util.bin2long(regTime.getData())));
        }
      }
      throw new UserNotFoundException();
    } catch (DatabaseException e) {
      severe("Exception while getting user", e);
      throw new UserNotFoundException(e);
    }
  }

  private DatabaseEntry makeNoncesKey(byte[] publicKey) {
    return new DatabaseEntry(makeBytes("users/nonces/".getBytes(), publicKey));
  }

  @Override
  public boolean checkAndAddNonce(Nonce nonce, byte[] publicKey) {
    Transaction txn = null;
    try {
      txn = env.beginTransaction(null, null);
      DatabaseEntry noncesKey = makeNoncesKey(publicKey);
      DatabaseEntry nonceValue = new DatabaseEntry(nonce.toToken());
      OperationStatus status = db.getSearchBoth(txn, noncesKey, nonceValue, null);
      if (status == OperationStatus.SUCCESS) {
        txn.commit();
        return false;// Nonce already used
      }
      status = db.put(txn, noncesKey, nonceValue);
      if (status == OperationStatus.SUCCESS) {
        txn.commit();
        return true;
      } else {
        txn.abort();
        return false;
      }
    } catch (DatabaseException e) {
      severe("Exception while checking nonce for user", e);
      try {
        if (txn != null)
          txn.abort();
      } catch (DatabaseException e1) {
        // we already had a failure, ignore this one.
      }
      return false;
    }
  }

  private byte[] makeLookupBytes(User user, byte[] lookup){
    return makeBytes("stores/".getBytes(), user.getPublicHash(), SEPARATOR, lookup);
  }

  private DatabaseEntry makeValueKey(byte[] lookup, byte[] revision){
    return new DatabaseEntry(makeBytes(lookup, SEPARATOR, revision));
  }

  @Override
  public Collection<RevValue> getRecord(User user, byte[] key) throws IOException {
    Transaction txn = null;
    try {
      txn = env.beginTransaction(null, null);
      OperationStatus lookupExists = db.getSearchBoth(txn, makeStoresKey(user), new DatabaseEntry(key), null);
      if (OperationStatus.SUCCESS != lookupExists){
        txn.commit();
        return null;
      }
      Collection<RevValue> collection = new ArrayList<RevValue>();
      Cursor cursor = db.openCursor(txn, null);
      try {
        byte[] lookup = makeLookupBytes(user, key);
        DatabaseEntry lookupKey = new DatabaseEntry(lookup);

        DatabaseEntry revision = new DatabaseEntry();
        DatabaseEntry value = new DatabaseEntry();
        DatabaseEntry revisionKey;
        OperationStatus status = cursor.getSearchKey(lookupKey, revision, null);
        for (; OperationStatus.SUCCESS == status; status =
            cursor.getNextDup(lookupKey, revision, null)) {
          revisionKey = makeValueKey(lookup, revision.getData());
          OperationStatus valStatus = db.get(txn, revisionKey, value, null);
          if (OperationStatus.SUCCESS == valStatus) {
            collection.add(new RevValue(revision.getData(), value.getData()));
          } else {
            // TODO(drt24) revision exists but value does not.
          }
        }
      } finally {
        cursor.close();
      }
      txn.commit();
      return collection;

    } catch (DatabaseException e) {
      severe("Exception while getting record", e);
      try {
        if (txn != null)
          txn.abort();
      } catch (DatabaseException e1) {
        // we already had a failure, ignore this one.
      }
      throw new IOException(e);
    }
  }

  @Override
  public RevValue getRevision(User user, byte[] key, byte[] revision) throws IOException {
    try {
      DatabaseEntry value = new DatabaseEntry();
      OperationStatus status = db.get(null, makeValueKey(makeLookupBytes(user, key), revision), value, null);
      if (OperationStatus.SUCCESS == status) {
        return new RevValue(revision, value.getData());
      }
      return null;
    } catch (DatabaseException e) {
      throw new IOException(e);
    }
  }

  @Override
  public Collection<byte[]> getIndices(User user) throws IOException {
    Transaction txn = null;
    Cursor cursor = null;
    try {
      txn = env.beginTransaction(null, null);
      Collection<byte[]> indices = new ArrayList<byte[]>();
      cursor = db.openCursor(txn, null);
      try {
        DatabaseEntry storeKey = makeStoresKey(user);
        DatabaseEntry index = new DatabaseEntry();
        for (OperationStatus indexStatus = cursor.getSearchKey(storeKey, index, null); OperationStatus.SUCCESS == indexStatus;
            indexStatus = cursor.getNextDup(storeKey, index, null)) {
          indices.add(index.getData());
        }
      } finally {
        cursor.close();
      }
      txn.commit();
      return indices;
    } catch (DatabaseException e) {
      try {
        if (cursor != null)
          cursor.close();
      } catch (DatabaseException e1) {
        // already caught one so ignore this one
      }
      try {
        if (txn != null)
          txn.abort();
      } catch (DatabaseException e1) {
        // already caught one so ignore this one
      }
      throw new IOException(e);
    }
  }

  @Override
  public Collection<byte[]> getRevisions(User user, byte[] key) throws IOException {
    Transaction txn = null;
    try {
      txn = env.beginTransaction(null, null);
      DatabaseEntry lookup = new DatabaseEntry(makeLookupBytes(user, key));
      DatabaseEntry revision = new DatabaseEntry();

      Collection<byte[]> revisions = new ArrayList<byte[]>();
      Cursor cursor = db.openCursor(txn, null);
      try {
        for (OperationStatus revisionStatus = cursor.getSearchKey(lookup, revision, null); OperationStatus.SUCCESS == revisionStatus;
            revisionStatus = cursor.getNextDup(lookup, revision, null)) {
          revisions.add(revision.getData());
        }
      } finally {
        cursor.close();
      }
      txn.commit();
      if (revisions.size() == 0){
        return null;
      }
      return revisions;
    } catch (DatabaseException e) {
      if (txn != null) {
        try {
          txn.abort();
        } catch (DatabaseException e1) {
          // we already had a failure, ignore this one.
        }
      }
      throw new IOException(e);
    }
  }

  private DatabaseEntry makeStoresKey(User user) {
    return new DatabaseEntry(makeBytes("stores/".getBytes(), user.getPublicHash()));
  }

  @Override
  public boolean putRecord(User user, byte[] key, byte[] revision, byte[] data) {
    Transaction txn = null;
    try {
      txn = env.beginTransaction(null, null);
      DatabaseEntry storesKey = makeStoresKey(user);
      DatabaseEntry lookup = new DatabaseEntry(key);
      OperationStatus lookupExists = db.getSearchBoth(txn, storesKey, lookup, null);
      if (OperationStatus.NOTFOUND == lookupExists){
        // insert lookup
        lookupExists = db.put(txn, storesKey, lookup);
      }
      if (OperationStatus.SUCCESS != lookupExists){
        txn.abort();
        return false;
      }
      byte[] lookupKey = makeLookupBytes(user,key);
      DatabaseEntry lookupEntry = new DatabaseEntry(lookupKey);
      DatabaseEntry revisionEntry = new DatabaseEntry(revision);
      OperationStatus revisionExists = db.getSearchBoth(txn, lookupEntry, revisionEntry, null);
      if (OperationStatus.SUCCESS == revisionExists){// already exists, abort
        txn.abort();
        return false;
      }
      // Does not exist, make it exist.
      revisionExists = db.put(txn, lookupEntry, revisionEntry);
      if (OperationStatus.SUCCESS != revisionExists){
        txn.abort();
        log.warning("Could not put revision: " + revisionExists.toString());
        return false;
      }
      OperationStatus putValue = db.put(txn, new DatabaseEntry(makeBytes(lookupKey,SEPARATOR,revision)), new DatabaseEntry(data));
      if (OperationStatus.SUCCESS != putValue){
        txn.abort();
        log.warning("Could not put value: " + putValue.toString());
        return false;
      }
      txn.commit();
      return true;
    } catch (DatabaseException e){
      severe("Exception while putting record", e);
      try {
        if (txn != null)
          txn.abort();
      } catch (DatabaseException e1) {
        // we already had a failure, ignore this one.
      }
      return false;
    }
  }

  private boolean deleteRevisions(User user, byte[] key, Transaction txn) {
    boolean didWork = false;

    Cursor cursor = db.openCursor(txn, null);
    try {
      DatabaseEntry revision = new DatabaseEntry();
      byte[] lookup = makeLookupBytes(user, key);
      DatabaseEntry lookupKey = new DatabaseEntry(lookup);
      DatabaseEntry revisionKey;
      for (OperationStatus revisionStatus = cursor.getSearchKey(lookupKey, revision, null); OperationStatus.SUCCESS == revisionStatus;
          revisionStatus = cursor.getNextDup(lookupKey, revision, null)) {
        revisionKey = new DatabaseEntry(makeBytes(lookup, SEPARATOR, revision.getData()));
        OperationStatus valueDelete = db.delete(txn, revisionKey);
        OperationStatus revisionDelete = cursor.delete();
        if (OperationStatus.SUCCESS == valueDelete || OperationStatus.SUCCESS == revisionDelete) {
          didWork = true;
        }
      }
    } finally {
      cursor.close();
    }
    return didWork;

  }

  @Override
  public boolean deleteRecord(User user, byte[] key) {
    Transaction txn = null;
    try {
      txn = env.beginTransaction(null, null);
      boolean result = false;
      Cursor cursor = db.openCursor(txn, null);
      try {
      OperationStatus lookupStatus =
          cursor.getSearchBoth(makeStoresKey(user), new DatabaseEntry(key), null);
      if (OperationStatus.SUCCESS == lookupStatus) {
        OperationStatus lookupDelete = cursor.delete();
        if (OperationStatus.SUCCESS == lookupDelete) {
          result = true;
        }
      }
      } finally {
        cursor.close();
      }
      result |= deleteRevisions(user, key, txn);
      txn.commit();
      return result;
    } catch (DatabaseException e) {
      severe("Exception while deleting record", e);
      try {
        if (txn != null)
          txn.abort();
      } catch (DatabaseException e1) {
        // we already had a failure, ignore this one.
      }
      return false;
    }
  }

  @Override
  public void clearOldNonces() {
    // TODO(drt24) implement clearOldNonces
    
  }

}
