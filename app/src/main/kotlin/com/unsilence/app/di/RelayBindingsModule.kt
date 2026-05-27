package com.unsilence.app.di

import com.unsilence.app.data.auth.KeyManager
import com.unsilence.app.data.auth.MuteKeyProvider
import com.unsilence.app.data.memory.MemoryEventStore
import com.unsilence.app.data.relay.ActiveSubsSource
import com.unsilence.app.data.relay.EventProcessor
import com.unsilence.app.data.relay.MesEventLoader
import com.unsilence.app.data.relay.RelayMetadataSource
import com.unsilence.app.data.relay.ReconnectSource
import com.unsilence.app.data.relay.RelayPool
import com.unsilence.app.data.relay.RelayTransport
import com.unsilence.app.data.relay.Subscription
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
    abstract fun bindMuteKeyProvider(impl: KeyManager): MuteKeyProvider

    @Binds
    abstract fun bindRelayMetadataSource(impl: MemoryEventStore): RelayMetadataSource

    @Binds
    abstract fun bindRelayTransport(impl: RelayPool): RelayTransport

    @Binds
    abstract fun bindReconnectSource(impl: RelayPool): ReconnectSource

    @Binds
    abstract fun bindTapRegistration(impl: EventProcessor): TapRegistration

    @Binds
    abstract fun bindActiveSubsSource(impl: Subscription): ActiveSubsSource

    @Binds
    abstract fun bindTimelineEventLoader(impl: MesEventLoader): TimelineEventLoader
}
