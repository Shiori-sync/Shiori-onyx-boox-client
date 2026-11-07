package com.onyx.android.sdk.note

import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import android.os.Parcelable.Creator

/** Reconstructed wire layout, verified against Lumi Notes 45256; no vendor implementation. */
class NoteContentIntentArgs private constructor(parcel: Parcel) : Parcelable {
    val docId: String?
    val content: String?
    val pfd: ParcelFileDescriptor?

    override fun writeToParcel(out: Parcel, flags: Int) {
        out.writeString(docId)
        out.writeString(content)
        out.writeParcelable(pfd, flags)
    }

    override fun describeContents(): Int {
        return if (pfd == null) 0 else Parcelable.CONTENTS_FILE_DESCRIPTOR
    }

    init {
        docId = parcel.readString()
        content = parcel.readString()
        pfd =
            parcel.readParcelable<ParcelFileDescriptor?>(
                ParcelFileDescriptor::class.java.getClassLoader()
            )
    }

    companion object {
        @JvmField
        val CREATOR: Creator<NoteContentIntentArgs?> =
            object : Creator<NoteContentIntentArgs?> {
                override fun createFromParcel(parcel: Parcel): NoteContentIntentArgs {
                    return NoteContentIntentArgs(parcel)
                }

                override fun newArray(n: Int): Array<NoteContentIntentArgs?> {
                    return arrayOfNulls<NoteContentIntentArgs>(n)
                }
            }
    }
}
