package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.UserEntity

internal fun profileMissingPicture(user: UserEntity?): Boolean =
    user?.picture.isNullOrBlank()
