package app.cash.paykit.core.impl

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.annotation.WorkerThread
import app.cash.paykit.core.BuildConfig
import app.cash.paykit.core.CashAppPayKit
import app.cash.paykit.core.CashAppPayKitListener
import app.cash.paykit.core.NetworkManager
import app.cash.paykit.core.PayKitLifecycleObserver
import app.cash.paykit.core.PayKitState
import app.cash.paykit.core.PayKitState.Approved
import app.cash.paykit.core.PayKitState.Authorizing
import app.cash.paykit.core.PayKitState.CreatingCustomerRequest
import app.cash.paykit.core.PayKitState.Declined
import app.cash.paykit.core.PayKitState.NotStarted
import app.cash.paykit.core.PayKitState.PayKitException
import app.cash.paykit.core.PayKitState.PollingTransactionStatus
import app.cash.paykit.core.PayKitState.ReadyToAuthorize
import app.cash.paykit.core.PayKitState.UpdatingCustomerRequest
import app.cash.paykit.core.exceptions.PayKitIntegrationException
import app.cash.paykit.core.models.common.NetworkResult.Failure
import app.cash.paykit.core.models.common.NetworkResult.Success
import app.cash.paykit.core.models.response.CustomerResponseData
import app.cash.paykit.core.models.sdk.PayKitPaymentAction
import app.cash.paykit.core.utils.orElse

/**
 * @param clientId Client Identifier that should be provided by Cash PayKit integration.
 * @param useSandboxEnvironment Specify what development environment should be used.
 */
internal class CashAppPayKitImpl(
  private val clientId: String,
  private val networkManager: NetworkManager,
  private val payKitLifecycleListener: PayKitLifecycleObserver,
  private val useSandboxEnvironment: Boolean = false,
) : CashAppPayKit, PayKitLifecycleListener {

  private var callbackListener: CashAppPayKitListener? = null

  private var customerResponseData: CustomerResponseData? = null

  private var currentState: PayKitState = NotStarted
    set(value) {
      field = value
      callbackListener?.payKitStateDidChange(value)
        .orElse {
          logError(
            "State changed to ${value.javaClass.simpleName}, but no listeners were notified." +
              "Make sure that you've used `registerForStateUpdates` to receive PayKit state updates.",
          )
        }
    }

  /**
   * Create customer request given a [PayKitPaymentAction].
   * Must be called from a background thread.
   *
   * @param paymentAction A wrapper class that contains all of the necessary ingredients for building a customer request.
   *                      Look at [PayKitPaymentAction] for more details.
   */
  @WorkerThread
  override fun createCustomerRequest(paymentAction: PayKitPaymentAction) {
    enforceRegisteredStateUpdatesListener()
    currentState = CreatingCustomerRequest
    val networkResult = networkManager.createCustomerRequest(clientId, paymentAction)
    when (networkResult) {
      is Failure -> {
        currentState = PayKitException(networkResult.exception)
      }
      is Success -> {
        customerResponseData = networkResult.data.customerResponseData
        currentState = ReadyToAuthorize(networkResult.data.customerResponseData)
      }
    }
  }

  /**
   * Update an existing customer request given its [requestId] an the updated definitions contained within [PayKitPaymentAction].
   * Must be called from a background thread.
   *
   * @param requestId ID of the request we intent do update.
   * @param paymentAction A wrapper class that contains all of the necessary ingredients for building a customer request.
   *                      Look at [PayKitPaymentAction] for more details.
   */
  @WorkerThread
  override fun updateCustomerRequest(
    requestId: String,
    paymentAction: PayKitPaymentAction,
  ) {
    enforceRegisteredStateUpdatesListener()
    currentState = UpdatingCustomerRequest
    val networkResult = networkManager.updateCustomerRequest(clientId, requestId, paymentAction)
    when (networkResult) {
      is Failure -> {
        currentState = PayKitException(networkResult.exception)
      }
      is Success -> {
        customerResponseData = networkResult.data.customerResponseData
        currentState = ReadyToAuthorize(networkResult.data.customerResponseData)
      }
    }
  }

  /**
   * Authorize a customer request. This function must be called AFTER `createCustomerRequest`.
   * Not doing so will result in an Exception in sandbox mode, and a silent error log in production.
   *
   * @param context Android context class.
   */
  @Throws(IllegalArgumentException::class, PayKitIntegrationException::class)
  override fun authorizeCustomerRequest(context: Context) {
    val customerData = customerResponseData

    if (customerData == null) {
      logAndSoftCrash(
        PayKitIntegrationException(
          "Can't call authorizeCustomerRequest user before calling `createCustomerRequest`. Alternatively provide your own customerData",
        ),
      )
      return
    }

    authorizeCustomerRequest(context, customerData)
  }

  /**
   * Authorize a customer request with a previously created `customerData`.
   * This function will set this SDK instance internal state to the `customerData` provided here as a function parameter.
   *
   */
  @Throws(IllegalArgumentException::class, RuntimeException::class)
  override fun authorizeCustomerRequest(
    context: Context,
    customerData: CustomerResponseData,
  ) {
    enforceRegisteredStateUpdatesListener()

    if (customerData.authFlowTriggers?.mobileUrl.isNullOrEmpty()) {
      throw IllegalArgumentException("customerData is missing redirect url")
    }
    // Open Mobile URL provided by backend response.
    val intent = Intent(Intent.ACTION_VIEW)
    intent.data = try {
      Uri.parse(customerData.authFlowTriggers?.mobileUrl)
    } catch (error: NullPointerException) {
      throw IllegalArgumentException("Cannot parse redirect url")
    }

    // Replace internal state.
    customerResponseData = customerData

    // Register for process lifecycle updates.
    payKitLifecycleListener.register(this)

    try {
      context.startActivity(intent)
    } catch (activityNotFoundException: ActivityNotFoundException) {
      throw RuntimeException("unable to open mobileUrl")
    }
    currentState = Authorizing
  }

  /**
   *  Register a [CashAppPayKitListener] to receive PayKit callbacks.
   */
  override fun registerForStateUpdates(listener: CashAppPayKitListener) {
    callbackListener = listener
  }

  /**
   *  Unregister any previously registered [CashAppPayKitListener] from PayKit updates.
   */
  override fun unregisterFromStateUpdates() {
    callbackListener = null
    payKitLifecycleListener.unregister(this)
  }

  private fun enforceRegisteredStateUpdatesListener() {
    if (callbackListener == null) {
      logAndSoftCrash(
        PayKitIntegrationException(
          "Shouldn't call this function before registering for state updates via `registerForStateUpdates`.",
        ),
      )
    }
  }

  private fun poolTransactionStatus() {
    Thread {
      val networkResult = networkManager.retrieveUpdatedRequestData(
        clientId,
        customerResponseData!!.id,
      )
      if (networkResult is Failure) {
        currentState = PayKitException(networkResult.exception)
        return@Thread
      }
      customerResponseData = (networkResult as Success).data.customerResponseData

      if (customerResponseData?.status == "APPROVED") {
        // Successful transaction.
        setStateFinished(true)
      } else {
        // If status is pending, schedule to check again.
        if (customerResponseData?.status == "PENDING") {
          // TODO: Add backoff strategy for long polling. ( https://www.notion.so/cashappcash/Implement-Long-pooling-retry-logic-a9af47e2db9242faa5d64df2596fd78e )
          Thread.sleep(500)
          poolTransactionStatus()
          return@Thread
        }

        // Unsuccessful transaction.
        setStateFinished(false)
      }
    }.start()
  }

  private fun logError(errorMessage: String) {
    Log.e("PayKit", errorMessage)
  }

  /**
   * This function will log in production, additionally it will throw an exception in sandbox or debug mode.
   */
  @Throws
  private fun logAndSoftCrash(exception: Exception) {
    logError("Error occurred. E.: $exception")
    if (useSandboxEnvironment || BuildConfig.DEBUG) {
      throw exception
    }
  }

  private fun setStateFinished(wasSuccessful: Boolean) {
    payKitLifecycleListener.unregister(this)
    currentState = if (wasSuccessful) {
      Approved(customerResponseData!!)
    } else {
      Declined
    }
  }

  /**
   * Lifecycle callbacks.
   */

  override fun onApplicationForegrounded() {
    logError("onApplicationForegrounded")
    if (currentState is Authorizing) {
      currentState = PollingTransactionStatus
      poolTransactionStatus()
    }
  }

  override fun onApplicationBackgrounded() {
    logError("onApplicationBackgrounded")
  }
}