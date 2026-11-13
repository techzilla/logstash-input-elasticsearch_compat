#!/usr/bin/env bash
# Regenerates the TLS test fixtures in this directory (test-only material; never use in production).
# Requires openssl and keytool (JDK). Passwords/passphrases are all "changeit".
set -euo pipefail
cd "$(dirname "$0")"
DAYS=36500
PASS=changeit

rm -f ./*.crt ./*.key ./*.p12 ./*.jks ./*.srl ./*.csr ./*.ext

# Trusted CA
openssl req -x509 -newkey rsa:2048 -nodes -days $DAYS -subj "/CN=escompat-test-ca" \
  -keyout ca.key -out ca.crt

# Server certificate (SAN localhost, 127.0.0.1, logstash)
openssl req -newkey rsa:2048 -nodes -subj "/CN=localhost" -keyout server.pkcs1.tmp -out server.csr
printf 'subjectAltName=DNS:localhost,IP:127.0.0.1,DNS:logstash\nextendedKeyUsage=serverAuth\n' > server.ext
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days $DAYS \
  -extfile server.ext -out server.crt
openssl pkcs8 -topk8 -nocrypt -in server.pkcs1.tmp -out server.key
openssl rsa -in server.key -traditional -out server.pkcs1.key
openssl pkcs8 -topk8 -v2 aes-256-cbc -passout pass:$PASS -in server.key -out server.encrypted.key
rm -f server.pkcs1.tmp

# Keystores and truststore
openssl pkcs12 -export -in server.crt -inkey server.key -certfile ca.crt -name server \
  -passout pass:$PASS -out server.p12
keytool -importkeystore -noprompt -srckeystore server.p12 -srcstoretype PKCS12 -srcstorepass $PASS \
  -destkeystore server.jks -deststoretype JKS -deststorepass $PASS
keytool -importcert -noprompt -alias ca -file ca.crt -keystore truststore.jks -storetype JKS \
  -storepass $PASS

# Trusted client certificate
openssl req -newkey rsa:2048 -nodes -subj "/CN=escompat-client" -keyout client.key -out client.csr
printf 'extendedKeyUsage=clientAuth\n' > client.ext
openssl x509 -req -in client.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days $DAYS \
  -extfile client.ext -out client.crt
openssl pkcs12 -export -in client.crt -inkey client.key -name client -passout pass:$PASS -out client.p12

# Untrusted CA and client
openssl req -x509 -newkey rsa:2048 -nodes -days $DAYS -subj "/CN=escompat-untrusted-ca" \
  -keyout untrusted-ca.key -out untrusted-ca.crt
openssl req -newkey rsa:2048 -nodes -subj "/CN=escompat-untrusted-client" \
  -keyout untrusted-client.key -out untrusted-client.csr
openssl x509 -req -in untrusted-client.csr -CA untrusted-ca.crt -CAkey untrusted-ca.key \
  -CAcreateserial -days $DAYS -extfile client.ext -out untrusted-client.crt
openssl pkcs12 -export -in untrusted-client.crt -inkey untrusted-client.key -name client \
  -passout pass:$PASS -out untrusted-client.p12

rm -f ./*.csr ./*.ext ./*.srl untrusted-ca.key
chmod 644 ./*.key ./*.crt ./*.p12 ./*.jks
echo "TLS fixtures regenerated"
