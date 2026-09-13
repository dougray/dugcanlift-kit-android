package com.dugcanlift.kit

fun Double.trimZeros(): String =
    if (this == Math.floor(this) && !isInfinite()) toInt().toString() else toString()
