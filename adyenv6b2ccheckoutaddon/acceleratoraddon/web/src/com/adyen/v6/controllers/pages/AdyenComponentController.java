/*
 *                        ######
 *                        ######
 *  ############    ####( ######  #####. ######  ############   ############
 *  #############  #####( ######  #####. ######  #############  #############
 *         ######  #####( ######  #####. ######  #####  ######  #####  ######
 *  ###### ######  #####( ######  #####. ######  #####  #####   #####  ######
 *  ###### ######  #####( ######  #####. ######  #####          #####  ######
 *  #############  #############  #############  #############  #####  ######
 *   ############   ############  #############   ############  #####  ######
 *                                       ######
 *                                #############
 *                                ############
 *
 *  Adyen Hybris Extension
 *
 *  Copyright (c) 2020 Adyen B.V.
 *  This file is open source and available under the MIT license.
 *  See the LICENSE file for more info.
 */
package com.adyen.v6.controllers.pages;

import com.adyen.model.checkout.PaymentsResponse;
import com.adyen.model.checkout.details.PayPalDetails;
import com.adyen.service.exception.ApiException;
import com.adyen.v6.exceptions.AdyenComponentException;
import com.adyen.v6.exceptions.AdyenNonAuthorizedPaymentException;
import com.adyen.v6.facades.AdyenCheckoutFacade;
import com.adyen.v6.forms.AdyenPaymentForm;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import de.hybris.platform.acceleratorfacades.flow.CheckoutFlowFacade;
import de.hybris.platform.acceleratorfacades.order.AcceleratorCheckoutFacade;
import de.hybris.platform.commercefacades.order.data.CartData;
import de.hybris.platform.commercefacades.user.data.AddressData;
import de.hybris.platform.order.InvalidCartException;
import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static com.adyen.v6.constants.AdyenControllerConstants.COMPONENT_PREFIX;

@Controller
@RequestMapping(COMPONENT_PREFIX)
public class AdyenComponentController {
    private static final Logger LOGGER = Logger.getLogger(AdyenComponentController.class);

    @Resource(name = "adyenCheckoutFacade")
    private AdyenCheckoutFacade adyenCheckoutFacade;

    @Resource(name = "checkoutFlowFacade")
    private CheckoutFlowFacade checkoutFlowFacade;

    @Resource(name = "acceleratorCheckoutFacade")
    private AcceleratorCheckoutFacade checkoutFacade;

    @RequestMapping(value = "/payment", method = RequestMethod.POST)
    @ResponseBody
    public String componentPayment(final Model model,
                                final HttpServletRequest request,
                                @Valid final AdyenPaymentForm adyenPaymentForm,
                                final BindingResult bindingResult) throws AdyenComponentException {
        LOGGER.debug("Component paymentForm: " + adyenPaymentForm);

        if(!isValidateSessionCart())
        {
            if (adyenPaymentForm.getBillingAddress() != null) {
                adyenPaymentForm.resetFormExceptBillingAddress();
            }
            throw new AdyenComponentException("checkout.error.paymentethod.formentry.invalid");
        }

        //Save payment information
        getAdyenCheckoutFacade().handlePaymentForm(adyenPaymentForm, bindingResult);
        if (bindingResult.hasGlobalErrors() || bindingResult.hasErrors()) {
            LOGGER.debug(bindingResult.getAllErrors().stream().map(DefaultMessageSourceResolvable::getCode).reduce((x, y) -> (x = x + y)));
            if (adyenPaymentForm.getBillingAddress() != null) {
                adyenPaymentForm.resetFormExceptBillingAddress();
            }
            throw new AdyenComponentException("checkout.error.paymentethod.formentry.invalid");
        }

        try {
            validateOrderForm();

            //Make payment request
            Gson gson = new GsonBuilder().disableHtmlEscaping().create();
            String payPalDetailsJson = adyenPaymentForm.getComponentData();
            PayPalDetails payPalDetails = gson.fromJson(payPalDetailsJson, PayPalDetails.class);

            final CartData cartData = getCheckoutFlowFacade().getCheckoutCart();

            PaymentsResponse paymentsResponse = getAdyenCheckoutFacade().componentPayment(request, cartData, payPalDetails);
            return gson.toJson(paymentsResponse);
        } catch (InvalidCartException e) {
            LOGGER.error("InvalidCartException", e);
            throw new AdyenComponentException(e.getMessage());
        }
        catch ( ApiException e) {
            LOGGER.error("ApiException", e);
            throw new AdyenComponentException("checkout.error.authorization.payment.refused");
        }  catch (AdyenNonAuthorizedPaymentException  e) {
            LOGGER.debug("AdyenNonAuthorizedPaymentException occurred. Payment is refused.");
            throw new AdyenComponentException("checkout.error.authorization.payment.refused");
        } catch (Exception e) {
            LOGGER.error("Exception", e);
            throw new AdyenComponentException("checkout.error.authorization.payment.error");
        }
    }

    @RequestMapping(value = "/submit-details", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public String submitDetails(final HttpServletRequest request) throws AdyenComponentException {
        try {
            String requestJsonString = IOUtils.toString(request.getInputStream(), StandardCharsets.UTF_8);
            JsonObject requestJson = new JsonParser().parse(requestJsonString).getAsJsonObject();

            Gson gson = new GsonBuilder().disableHtmlEscaping().create();
            Type mapType = new TypeToken<Map<String, String>>() {}.getType();
            Map<String, String> details = gson.fromJson(requestJson.get("details"), mapType);
            String paymentData = gson.fromJson(requestJson.get("paymentData"), String.class);

            PaymentsResponse paymentsResponse = getAdyenCheckoutFacade().componentDetails(request, details, paymentData);
            return gson.toJson(paymentsResponse);
        } catch (ApiException e) {
            LOGGER.error("ApiException", e);
            throw new AdyenComponentException("checkout.error.authorization.payment.refused");
        } catch (Exception e) {
            LOGGER.error("Exception", e);
            throw new AdyenComponentException("checkout.error.authorization.payment.error");
        }
    }

    @ExceptionHandler(value = AdyenComponentException.class)
    public AdyenComponentException adyenComponentExceptionHandler(AdyenComponentException e, HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        response.getWriter().write(e.getMessage());
        response.getWriter().flush();
        response.getWriter().close();
        return e;
    }

    /**
     * Validates the order form before to filter out invalid order states
     *
     * @return True if the order form is invalid and false if everything is valid.
     */
    protected void validateOrderForm() throws InvalidCartException {
        if (getCheckoutFlowFacade().hasNoDeliveryAddress()) {
            throw new InvalidCartException("checkout.deliveryAddress.notSelected");
        }

        if (getCheckoutFlowFacade().hasNoDeliveryMode()) {
            throw new InvalidCartException("checkout.deliveryMethod.notSelected");
        }

        if (getCheckoutFlowFacade().hasNoPaymentInfo()) {
            throw new InvalidCartException("checkout.paymentMethod.notSelected");
        }

        final CartData cartData = getCheckoutFacade().getCheckoutCart();

        if (! getCheckoutFacade().containsTaxValues()) {
            LOGGER.error(String.format("Cart %s does not have any tax values, which means the tax cacluation was not properly done, placement of order can't continue", cartData.getCode()));
            throw new InvalidCartException("checkout.error.tax.missing");
        }

        if (! cartData.isCalculated()) {
            LOGGER.error(String.format("Cart %s has a calculated flag of FALSE, placement of order can't continue", cartData.getCode()));
            throw new InvalidCartException("checkout.error.cart.notcalculated");
        }
    }

    public AdyenCheckoutFacade getAdyenCheckoutFacade() {
        return adyenCheckoutFacade;
    }

    public void setAdyenCheckoutFacade(AdyenCheckoutFacade adyenCheckoutFacade) {
        this.adyenCheckoutFacade = adyenCheckoutFacade;
    }

    public CheckoutFlowFacade getCheckoutFlowFacade() {
        return checkoutFlowFacade;
    }

    public void setCheckoutFlowFacade(CheckoutFlowFacade checkoutFlowFacade) {
        this.checkoutFlowFacade = checkoutFlowFacade;
    }

    public AcceleratorCheckoutFacade getCheckoutFacade() {
        return checkoutFacade;
    }

    public void setCheckoutFacade(AcceleratorCheckoutFacade checkoutFacade) {
        this.checkoutFacade = checkoutFacade;
    }

    private boolean isValidateSessionCart() {
        CartData cart = getCheckoutFacade().getCheckoutCart();
        final AddressData deliveryAddress = cart.getDeliveryAddress();
        if (deliveryAddress == null || deliveryAddress.getCountry() == null || deliveryAddress.getCountry().getIsocode() == null) {
            return false;
        }
        return true;

    }
}
