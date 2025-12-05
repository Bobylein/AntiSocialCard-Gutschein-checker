#!/bin/bash

# Android Keystore Setup Script
# This script creates a keystore file for signing Android release builds

set -e

KEYSTORE_FILE="app/release-key.jks"
KEYSTORE_PROPERTIES="app/keystore.properties"
KEY_ALIAS="release-key-alias"
VALIDITY_DAYS=10000

echo "=========================================="
echo "Android Keystore Setup"
echo "=========================================="
echo ""
echo "This script will create a keystore file for signing your Android app."
echo "You'll need to provide:"
echo "  - Keystore password (used to protect the keystore file)"
echo "  - Key password (can be same as keystore password)"
echo "  - Certificate information (name, organization, etc.)"
echo ""
echo "IMPORTANT: Keep these passwords safe! You'll need them to sign future releases."
echo ""

# Check if keystore already exists
if [ -f "$KEYSTORE_FILE" ]; then
    echo "WARNING: Keystore file already exists at $KEYSTORE_FILE"
    read -p "Do you want to overwrite it? (yes/no): " overwrite
    if [ "$overwrite" != "yes" ]; then
        echo "Aborted. Keystore not created."
        exit 1
    fi
    rm -f "$KEYSTORE_FILE"
fi

# Prompt for keystore password
echo ""
read -sp "Enter keystore password: " KEYSTORE_PASSWORD
echo ""
read -sp "Re-enter keystore password to confirm: " KEYSTORE_PASSWORD_CONFIRM
echo ""

if [ "$KEYSTORE_PASSWORD" != "$KEYSTORE_PASSWORD_CONFIRM" ]; then
    echo "ERROR: Passwords do not match!"
    exit 1
fi

# Prompt for key password
echo ""
read -sp "Enter key password (press Enter to use same as keystore password): " KEY_PASSWORD
echo ""

if [ -z "$KEY_PASSWORD" ]; then
    KEY_PASSWORD="$KEYSTORE_PASSWORD"
    echo "Using keystore password as key password."
else
    read -sp "Re-enter key password to confirm: " KEY_PASSWORD_CONFIRM
    echo ""
    if [ "$KEY_PASSWORD" != "$KEY_PASSWORD_CONFIRM" ]; then
        echo "ERROR: Key passwords do not match!"
        exit 1
    fi
fi

# Prompt for certificate information
echo ""
echo "Now enter certificate information:"
read -p "Your name or organization name: " CERT_NAME
read -p "Organizational Unit (optional, press Enter to skip): " CERT_OU
read -p "Organization: " CERT_ORG
read -p "City or Locality: " CERT_CITY
read -p "State or Province: " CERT_STATE
read -p "Two-letter country code (e.g., US, DE): " CERT_COUNTRY

# Validate country code
if [ ${#CERT_COUNTRY} -ne 2 ]; then
    echo "ERROR: Country code must be exactly 2 letters!"
    exit 1
fi

# Build distinguished name
DN="CN=$CERT_NAME"
if [ -n "$CERT_OU" ]; then
    DN="$DN, OU=$CERT_OU"
fi
DN="$DN, O=$CERT_ORG, L=$CERT_CITY, ST=$CERT_STATE, C=$CERT_COUNTRY"

echo ""
echo "Creating keystore..."
echo "Distinguished Name: $DN"
echo ""

# Create keystore using keytool
keytool -genkey -v \
    -keystore "$KEYSTORE_FILE" \
    -alias "$KEY_ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity $VALIDITY_DAYS \
    -storepass "$KEYSTORE_PASSWORD" \
    -keypass "$KEY_PASSWORD" \
    -dname "$DN"

if [ $? -ne 0 ]; then
    echo "ERROR: Failed to create keystore!"
    exit 1
fi

echo ""
echo "Keystore created successfully at $KEYSTORE_FILE"
echo ""

# Create keystore.properties file
echo "Creating keystore.properties file..."
cat > "$KEYSTORE_PROPERTIES" << EOF
# Android Keystore Configuration
# DO NOT COMMIT THIS FILE TO VERSION CONTROL!
# This file contains sensitive credentials.

storePassword=$KEYSTORE_PASSWORD
keyPassword=$KEY_PASSWORD
keyAlias=$KEY_ALIAS
storeFile=release-key.jks
EOF

# Set proper file permissions
chmod 600 "$KEYSTORE_PROPERTIES"
chmod 644 "$KEYSTORE_FILE"

echo "keystore.properties created at $KEYSTORE_PROPERTIES"
echo ""
echo "=========================================="
echo "Setup Complete!"
echo "=========================================="
echo ""
echo "Keystore file: $KEYSTORE_FILE"
echo "Properties file: $KEYSTORE_PROPERTIES"
echo ""
echo "Next steps:"
echo "  1. Build a signed release APK: ./gradlew assembleRelease"
echo "  2. The signed APK will be at: app/build/outputs/apk/release/app-release.apk"
echo ""
echo "IMPORTANT:"
echo "  - Keep your keystore file and passwords safe!"
echo "  - Back up your keystore file to a secure location"
echo "  - If you lose the keystore, you cannot update your app on Google Play"
echo ""


