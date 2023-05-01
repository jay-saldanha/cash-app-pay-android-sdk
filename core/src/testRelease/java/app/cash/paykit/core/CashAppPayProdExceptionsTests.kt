/*
 * Copyright (C) 2023 Cash App
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
package app.cash.paykit.core

import app.cash.paykit.core.impl.CashAppCashAppPayImpl
import io.mockk.MockKAnnotations
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import org.junit.Before
import org.junit.Test

class CashAppPayProdExceptionsTests {

  @MockK(relaxed = true)
  private lateinit var networkManager: NetworkManager

  @Before
  fun setup() {
    MockKAnnotations.init(this)
  }

  @Test
  fun `softCrashOrStateException should NOT crash in prod`() {
    val payKit = createPayKit(useSandboxEnvironment = false)
    val listener = mockk<CashAppPayListener>(relaxed = true)
    payKit.registerForStateUpdates(listener)
    payKit.createCustomerRequest(emptyList(), FakeData.REDIRECT_URI)
  }

  private fun createPayKit(useSandboxEnvironment: Boolean) =
    CashAppCashAppPayImpl(
      clientId = FakeData.CLIENT_ID,
      networkManager = networkManager,
      payKitLifecycleListener = mockk(relaxed = true),
      useSandboxEnvironment = useSandboxEnvironment,
      analyticsEventDispatcher = mockk(relaxed = true),
    )
}
