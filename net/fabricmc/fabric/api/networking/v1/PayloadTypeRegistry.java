/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.fabric.api.networking.v1;

import org.jetbrains.annotations.ApiStatus;
import net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl;
import net.minecraft.class_2540;
import net.minecraft.class_8710;
import net.minecraft.class_9129;
import net.minecraft.class_9139;

/**
 * A registry for payload types.
 */
@ApiStatus.NonExtendable
public interface PayloadTypeRegistry<B extends class_2540> {
	/**
	 * Registers a custom payload type.
	 *
	 * <p>This must be done on both the sending and receiving side, usually during mod initialization
	 * and <strong>before registering a packet handler</strong>.
	 *
	 * @param id    the id of the payload type
	 * @param codec the codec for the payload type
	 * @param <T>   the payload type
	 * @return the registered payload type
	 */
	<T extends class_8710> class_8710.class_9155<? super B, T> register(class_8710.class_9154<T> id, class_9139<? super B, T> codec);

	/**
	 * @return the {@link PayloadTypeRegistry} instance for the client to server configuration channel.
	 */
	static PayloadTypeRegistry<class_2540> configurationC2S() {
		return PayloadTypeRegistryImpl.CONFIGURATION_C2S;
	}

	/**
	 * @return the {@link PayloadTypeRegistry} instance for the server to client configuration channel.
	 */
	static PayloadTypeRegistry<class_2540> configurationS2C() {
		return PayloadTypeRegistryImpl.CONFIGURATION_S2C;
	}

	/**
	 * @return the {@link PayloadTypeRegistry} instance for the client to server play channel.
	 */
	static PayloadTypeRegistry<class_9129> playC2S() {
		return PayloadTypeRegistryImpl.PLAY_C2S;
	}

	/**
	 * @return the {@link PayloadTypeRegistry} instance for the server to client play channel.
	 */
	static PayloadTypeRegistry<class_9129> playS2C() {
		return PayloadTypeRegistryImpl.PLAY_S2C;
	}
}
