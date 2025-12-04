package com.antisocial.giftcardchecker.utils

import android.content.Intent
import android.os.Build
import android.os.Parcelable
import java.io.Serializable

/**
 * Extension functions for Intent to handle deprecated API usage in a compatible way.
 * These functions provide a unified API that works across all Android versions.
 */

/**
 * Gets a Parcelable extra from an Intent in a version-compatible way.
 * Replaces the deprecated getParcelableExtra() method.
 *
 * @param key The name of the desired item
 * @return The Parcelable extra, or null if not found
 */
inline fun <reified T : Parcelable> Intent.getParcelableExtraCompat(key: String): T? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }
}

/**
 * Gets a Serializable extra from an Intent in a version-compatible way.
 * Replaces the deprecated getSerializableExtra() method.
 *
 * @param key The name of the desired item
 * @return The Serializable extra, or null if not found
 */
inline fun <reified T : Serializable> Intent.getSerializableExtraCompat(key: String): T? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getSerializableExtra(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getSerializableExtra(key) as? T
    }
}

/**
 * Gets a Parcelable ArrayList extra from an Intent in a version-compatible way.
 * Replaces the deprecated getParcelableArrayListExtra() method.
 *
 * @param key The name of the desired item
 * @return The Parcelable ArrayList extra, or null if not found
 */
inline fun <reified T : Parcelable> Intent.getParcelableArrayListExtraCompat(key: String): ArrayList<T>? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayListExtra(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableArrayListExtra(key)
    }
}

/**
 * Gets a Parcelable array extra from an Intent in a version-compatible way.
 * Replaces the deprecated getParcelableArrayExtra() method.
 *
 * @param key The name of the desired item
 * @return The Parcelable array extra, or null if not found
 */
inline fun <reified T : Parcelable> Intent.getParcelableArrayExtraCompat(key: String): Array<T>? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayExtra(key, T::class.java)
    } else {
        @Suppress("DEPRECATION", "UNCHECKED_CAST")
        getParcelableArrayExtra(key) as? Array<T>
    }
}
