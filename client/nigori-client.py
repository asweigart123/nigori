#!/usr/local/bin/python

from base64 import urlsafe_b64encode as b64enc, urlsafe_b64decode as b64dec
from Crypto.Cipher import AES
from Crypto.Hash import HMAC
from Crypto.Hash import SHA256
from Crypto.Util import randpool
from nigori import SchnorrSigner, concat, int2bin

import httplib
import random
import simplejson
import sys
import time
import urllib

class KeyDeriver:
  def __init__(self, password):
    self.crypt = SHA256.new(password).digest()
    self.mac = SHA256.new(self.crypt).digest()
    self.authenticate = SHA256.new(self.mac).digest()

  def encryptWithIV(self, plain, iv):
    crypter = AES.new(self.crypt, AES.MODE_CBC, iv)
    pad = 16 - len(plain) % 16
    c = '%c' % pad
    for i in range(pad):
      plain = plain + c
    crypted = crypter.encrypt(plain)
    hmac = HMAC.new(self.mac, crypted)
    crypted = crypted + hmac.digest()
    return crypted

  def encrypt(self, plain):
    pool = randpool.RandomPool()
    iv = pool.get_bytes(16)
    return b64enc(iv + self.encryptWithIV(plain, iv))

  def permute(self, plain):
    iv = ""
    for i in range(16):
      iv = iv + ("%c" % 0)
    return b64enc(self.encryptWithIV(plain, iv))

  def decrypt(self, crypted):
    crypted = b64dec(crypted)
    l = len(crypted)
    if l < 32:
      raise ValueError("value too short")
    mac = crypted[l-16:]
    iv = crypted[:16]
    crypted = crypted [16:l-16]
    hmac = HMAC.new(self.mac, crypted)
    if mac != hmac.digest():
      raise ValueError("mac doesn't match")
    crypter = AES.new(self.crypt, AES.MODE_CBC, iv)
    plain = crypter.decrypt(crypted)
    c = plain[-1]
    for i in range(-1, -ord(c), -1):
      if plain[i] != c:
        raise ValueError("padding error")
    plain = plain[:-ord(c)]
    return plain

  def schnorr(self):
    return SchnorrSigner(self.authenticate)

def connect():
  return httplib.HTTPConnection(server, port)

def register(user, password):
  keys = KeyDeriver(password)
  schnorr = keys.schnorr()
  public = b64enc(schnorr.public())
  params = urllib.urlencode({"user": user, "publicKey": public})
  headers = {"Content-Type": "application/x-www-form-urlencoded",
             "Accept": "text/plain" }
  conn = connect()
  conn.request("POST", "/register", params, headers)
  response = conn.getresponse()
  print response.status, response.reason
  print response.read()

def makeAuthParams(user, password):
  # FIXME: include server name, user name in t
  t = "%d:%d" % (int(time.time()), random.SystemRandom().getrandbits(20))
  keys = KeyDeriver(password)
  schnorr = keys.schnorr()
  (e,s) = schnorr.sign(t)
  params = {"user": user,
            "t": t,
            "e": b64enc(e),
            "s": b64enc(s)}
  return params

def do_auth(params):
  params = urllib.urlencode(params)
  headers = {"Content-Type": "application/x-www-form-urlencoded"}
  conn = connect()
  conn.request("POST", "/authenticate", params, headers)
  response = conn.getresponse()
  print response.status, response.reason
  print response.read()

def authenticate(user, password):
  params = makeAuthParams(user, password)
  do_auth(params)
  # test replay attack
  print "Replaying: this should fail"
  do_auth(params)
  
def getList(user, password, type, name):
  params = makeAuthParams(user, password)
  keys = KeyDeriver(password)
  params['name'] = keys.permute(concat([int2bin(type), name]))
  conn = connect()
  conn.request("GET", "/list-resource?" + urllib.urlencode(params))
  response = conn.getresponse()
  if response.status != 200:
    # FIXME: define a ProtocolError, perhaps?
    raise LookupError("HTTP error: %d %s" % (response.status, response.reason))
  json = response.read()
  records = simplejson.loads(json)
  keys = KeyDeriver(password)
  for record in records:
    value = keys.decrypt(record['value'])
    print "%d at %f: %s" % (record['version'], record['creationTime'], value)

def add(user, password, type, name, value):
  params = makeAuthParams(user, password)
  keys = KeyDeriver(password)
  params['name'] = keys.permute(concat([int2bin(type), name]))
  params['value'] = keys.encrypt(value)
  params = urllib.urlencode(params)
  headers = {"Content-Type": "application/x-www-form-urlencoded",
             "Accept": "text/plain" }
  conn = connect()
  conn.request("POST", "/add-resource", params, headers)
  response = conn.getresponse()
  print response.status, response.reason
  print response.read()

def initSplit(user, password, splits):
  add(user, password, 2, "split servers", concat(splits))

def main():
  global server, port
  server = sys.argv[1]
  port = int(sys.argv[2])
  action = sys.argv[3]
  if action == "get":
    getList(sys.argv[4], sys.argv[5], 1, sys.argv[6])
  elif action == "add":
    add(sys.argv[4], sys.argv[5], 1, sys.argv[6], sys.argv[7])
  elif action == "register":
    register(sys.argv[4], sys.argv[5])
  elif action == "authenticate":
    authenticate(sys.argv[4], sys.argv[5])
  elif action == "create-split":
    initSplit(sys.argv[4], sys.argv[5], sys.argv[6:])
  else:
    raise ValueError("Unrecognised action: " + action)

if __name__ == "__main__":
  main()