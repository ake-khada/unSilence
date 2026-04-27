package com.unsilence.app.di

import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.relay.EventProcessor
import com.unsilence.app.data.relay.MesEventLoader
import com.unsilence.app.data.relay.RelayMetadataSource
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.RelayTransport
import com.unsilence.app.data.relay.TapRegistration
import com.unsilence.app.data.relay.TimelineEventLoader
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class RelayBindingsModule {
    @Binds
    abstract fun bindRelayMetadataSource(impl: MemoryEventStore): RelayMetadataSource

    @Binds
    abstract fun bindRelayTransport(impl: RelayPool): RelayTransport

    @Binds
    abstract fun bindTapRegistration(impl: EventProcessor): TapRegistration

    @Binds
    abstract fun bindTimelineEventLoader(impl: MesEventLoader): TimelineEventLoader
}
