package com.dieff.aurelian.foregroundService.data.manager


object HalfPrecisionConverter {
    fun toFloat(hbits: Int): Float {
        var mant = hbits and 0x03ff // 10 bits mantissa
        var exp = hbits and 0x7c00 // 5 bits exponent
        if (exp == 0x7c00) // NaN/Inf
            exp = 0x3fc00 // -> NaN/Inf
        else if (exp != 0) // normalized value
        {
            exp += 0x1c000 // exp - 15 + 127
            if (mant == 0 && exp > 0x1c400) // smooth transition
                return java.lang.Float.intBitsToFloat(hbits and 0x8000 shl 16 or (exp shl 13) or 0x3ff)
        } else if (mant != 0) // && exp==0 -> subnormal
        {
            exp = 0x1c400 // make it normal
            do {
                mant = mant shl 1 // mantissa * 2
                exp -= 0x400 // decrease exp by 1
            } while (mant and 0x400 == 0) // while not normal
            mant = mant and 0x3ff // discard subnormal bit
        } // else +/-0 -> +/-0
        return java.lang.Float.intBitsToFloat(// combine all parts
            hbits and 0x8000 shl 16 // sign  << ( 31 - 15 )
                    or (exp or mant) shl 13
        ) // value << ( 23 - 10 )
    }

    fun fromFloat(fval: Float): Int {
        val fbits = java.lang.Float.floatToIntBits(fval)
        val sign = fbits ushr 16 and 0x8000 // sign only
        var `val` = (fbits and 0x7fffffff) + 0x1000 // rounded value
        if (`val` >= 0x47800000) // might be or become NaN/Inf
        {                                     // avoid Inf due to rounding
            return if (fbits and 0x7fffffff >= 0x47800000) {                                 // is or must become NaN/Inf
                if (`val` < 0x7f800000) sign or 0x7c00 else sign or 0x7c00 or ( // remains +/-Inf or NaN
                        fbits and 0x007fffff ushr 13) // make it +/-Inf
                // keep NaN (and Inf) bits
            } else sign or 0x7bff
            // unrounded not quite Inf
        }
        if (`val` >= 0x38800000) // remains normalized value
            return sign or (`val` - 0x38000000 ushr 13) // exp - 127 + 15
        if (`val` < 0x33000000) // too small for subnormal
            return sign // becomes +/-0
        `val` = fbits and 0x7fffffff ushr 23 // tmp exp for subnormal calc
        return sign or ((fbits and 0x7fffff or 0x800000) // add subnormal bit
                + (0x800000 ushr `val` - 102) // round depending on cut off
                ushr 126 - `val`) // div by 2^(1-(exp-127+15)) and >> 13 | exp=0
    }
}

