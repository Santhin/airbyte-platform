/*
 * Copyright (c) 2020-2025 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.oauth.flows;

import io.airbyte.oauth.BaseOAuthFlow;

public class LeverOAuthFlowTest extends BaseOAuthFlowTest {

  @Override
  protected BaseOAuthFlow getOAuthFlow() {
    return new HarvestOAuthFlow(getHttpClient(), this::getConstantState);
  }

  @Override
  protected String getExpectedConsentUrl() {
    return "https://id.getharvest.com/oauth2/authorize?client_id=test_client_id&response_type=code&redirect_uri=https%3A%2F%2Fairbyte.io&state=state";
  }

}
