/*
 * Copyright 2020 The Exonum Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.exonum.binding.core.service;

import com.exonum.binding.common.crypto.PublicKey;
import com.exonum.binding.common.hash.HashCode;
import com.exonum.binding.core.blockchain.BlockchainData;
import com.google.auto.value.AutoValue;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Default implementation of the transaction context.
 */
@AutoValue
abstract class InternalExecutionContext implements ExecutionContext {

  public static InternalExecutionContext newInstance(BlockchainData blockchainData,
      @Nullable HashCode txMessageHash, @Nullable PublicKey authorPk, String serviceName,
      int serviceId) {
    var txMessageHashOpt = Optional.ofNullable(txMessageHash);
    var authorPkOpt = Optional.ofNullable(authorPk);
    return new AutoValue_InternalExecutionContext(blockchainData, txMessageHashOpt, authorPkOpt,
        serviceName, serviceId);
  }
}
