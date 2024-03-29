/*
 * Copyright (C) 2022 The Android Open Source Project
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
 */

package com.android.server.nearby.util.encryption;

import static com.android.server.nearby.NearbyService.TAG;

import android.security.keystore.KeyProperties;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.Mac;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * {@link android.nearby.BroadcastRequest#PRESENCE_VERSION_V1} for encryption and decryption.
 */
public class CryptorImpV1 implements Cryptor {

    /**
     * In the form of "algorithm/mode/padding". Must be the same across broadcast and scan devices.
     */
    private static final String CIPHER_ALGORITHM = "AES/CTR/NoPadding";

    private static final String HMAC_SHA256_ALGORITHM = "HmacSHA256";

    @VisibleForTesting
    static final String ENCRYPT_ALGORITHM = KeyProperties.KEY_ALGORITHM_AES;

    /** AES only supports key sizes of 16, 24 or 32 bytes. */
    private static final int AUTHENTICITY_KEY_BYTE_SIZE = 16;

    /** Length of encryption key required by AES/GCM encryption. */
    private static final int ENCRYPTION_KEY_SIZE = 32;

    /** Length of salt required by AES/GCM encryption. */
    private static final int AES_CTR_IV_SIZE = 16;

    /** Length HMAC tag */
    public static final int HMAC_TAG_SIZE = 16;

    // 4 16 byte arrays known by both the encryptor and decryptor.
    private static final byte[] AK_IV =
            new byte[] {12, -59, 19, 23, 96, 57, -59, 19, 117, -31, -116, -61, 86, -25, -33, -78};
    private static final byte[] ASALT_IV =
            new byte[] {111, 48, -83, -79, -10, -102, -16, 73, 43, 55, 102, -127, 58, -19, -113, 4};
    private static final byte[] HK_IV =
            new byte[] {12, -59, 19, 23, 96, 57, -59, 19, 117, -31, -116, -61, 86, -25, -33, -78};

    // Lazily instantiated when {@link #getInstance()} is called.
    @Nullable private static CryptorImpV1 sCryptor;

    /** Returns an instance of CryptorImpV1. */
    public static CryptorImpV1 getInstance() {
        if (sCryptor == null) {
            sCryptor = new CryptorImpV1();
        }
        return sCryptor;
    }

    private CryptorImpV1() {
    }

    @Nullable
    @Override
    public byte[] encrypt(byte[] data, byte[] salt, byte[] authenticityKey) {
        if (authenticityKey.length != AUTHENTICITY_KEY_BYTE_SIZE) {
            Log.w(TAG, "Illegal authenticity key size");
            return null;
        }

        // Generates a 32 bytes encryption key from authenticity_key
        byte[] encryptionKey = computeHkdf(authenticityKey, AK_IV, ENCRYPTION_KEY_SIZE);
        if (encryptionKey == null) {
            Log.e(TAG, "Failed to generate encryption key.");
            return null;
        }

        // Encrypts the data using the encryption key
        SecretKey secretKey = new SecretKeySpec(encryptionKey, ENCRYPT_ALGORITHM);
        Cipher cipher;
        try {
            cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
            Log.e(TAG, "Failed to encrypt with secret key.", e);
            return null;
        }
        byte[] asalt = computeHkdf(salt, ASALT_IV, AES_CTR_IV_SIZE);
        if (asalt == null) {
            Log.e(TAG, "Failed to generate salt.");
            return null;
        }
        try {
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new IvParameterSpec(asalt));
        } catch (InvalidKeyException | InvalidAlgorithmParameterException e) {
            Log.e(TAG, "Failed to initialize cipher.", e);
            return null;
        }
        try {
            return cipher.doFinal(data);
        } catch (IllegalBlockSizeException | BadPaddingException e) {
            Log.e(TAG, "Failed to encrypt with secret key.", e);
            return null;
        }
    }

    @Nullable
    @Override
    public byte[] decrypt(byte[] encryptedData, byte[] salt, byte[] authenticityKey) {
        if (authenticityKey.length != AUTHENTICITY_KEY_BYTE_SIZE) {
            Log.w(TAG, "Illegal authenticity key size");
            return null;
        }

        // Generates a 32 bytes encryption key from authenticity_key
        byte[] encryptionKey = computeHkdf(authenticityKey, AK_IV, ENCRYPTION_KEY_SIZE);
        if (encryptionKey == null) {
            Log.e(TAG, "Failed to generate encryption key.");
            return null;
        }

        // Decrypts the data using the encryption key
        SecretKey secretKey = new SecretKeySpec(encryptionKey, ENCRYPT_ALGORITHM);
        Cipher cipher;
        try {
            cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
            Log.e(TAG, "Failed to get cipher instance.", e);
            return null;
        }
        byte[] asalt = computeHkdf(salt, ASALT_IV, AES_CTR_IV_SIZE);
        if (asalt == null) {
            return null;
        }
        try {
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(asalt));
        } catch (InvalidKeyException | InvalidAlgorithmParameterException e) {
            Log.e(TAG, "Failed to initialize cipher.", e);
            return null;
        }

        try {
            return cipher.doFinal(encryptedData);
        } catch (IllegalBlockSizeException | BadPaddingException e) {
            Log.e(TAG, "Failed to decrypt bytes with secret key.", e);
            return null;
        }
    }

    @Override
    @Nullable
    public byte[] sign(byte[] data, byte[] key) {
        return generateHmacTag(data, key);
    }

    @Override
    public boolean verify(byte[] data, byte[] key, byte[] signature) {
        return Arrays.equals(sign(data, key), signature);
    }

    /** Generates a 16 bytes HMAC tag. This is used for decryptor to verify if the computed HMAC tag
     * is equal to HMAC tag in advertisement to see data integrity. */
    @Nullable
    @VisibleForTesting
    byte[] generateHmacTag(byte[] data, byte[] authenticityKey) {
        if (data == null || authenticityKey == null) {
            Log.e(TAG, "Not generate HMAC tag because of invalid data input.");
            return null;
        }

        if (authenticityKey.length != AUTHENTICITY_KEY_BYTE_SIZE) {
            Log.e(TAG, "Illegal authenticity key size");
            return null;
        }

        // Generates a 32 bytes HMAC key from authenticity_key
        byte[] hmacKey = computeHkdf(authenticityKey, HK_IV, AES_CTR_IV_SIZE);
        if (hmacKey == null) {
            Log.e(TAG, "Failed to generate HMAC key.");
            return null;
        }

        // Generates a 16 bytes HMAC tag from authenticity_key
        return computeHkdf(data, hmacKey, HMAC_TAG_SIZE);
    }

    /**
     * A HAMC sha256 based HKDF algorithm to pseudo randomly hash data and salt into a byte array of
     * given size.
     */
    // Based on google3/third_party/tink/java/src/main/java/com/google/crypto/tink/subtle/Hkdf.java
    @Nullable
    @VisibleForTesting
    static byte[] computeHkdf(byte[] ikm, byte[] salt, int size) {
        Mac mac;
        try {
            mac = Mac.getInstance(HMAC_SHA256_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            Log.w(TAG, "HMAC_SHA256_ALGORITHM is not supported.", e);
            return null;
        }

        if (size > 255 * mac.getMacLength()) {
            Log.w(TAG, "Size too large.");
            return null;
        }

        if (salt.length == 0) {
            Log.w(TAG, "Salt cannot be empty.");
            return null;
        }

        try {
            mac.init(new SecretKeySpec(salt, HMAC_SHA256_ALGORITHM));
        } catch (InvalidKeyException e) {
            Log.w(TAG, "Invalid key.", e);
            return null;
        }

        byte[] prk = mac.doFinal(ikm);
        byte[] result = new byte[size];
        try {
            mac.init(new SecretKeySpec(prk, HMAC_SHA256_ALGORITHM));
        } catch (InvalidKeyException e) {
            Log.w(TAG, "Invalid key.", e);
            return null;
        }

        byte[] digest = new byte[0];
        int ctr = 1;
        int pos = 0;
        while (true) {
            mac.update(digest);
            mac.update((byte) ctr);
            digest = mac.doFinal();
            if (pos + digest.length < size) {
                System.arraycopy(digest, 0, result, pos, digest.length);
                pos += digest.length;
                ctr++;
            } else {
                System.arraycopy(digest, 0, result, pos, size - pos);
                break;
            }
        }

        return result;
    }
}
