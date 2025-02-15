import { useCallback } from "react";

import { Connector, ConnectorDefinition } from "core/domain/connector";
import { SynchronousJobRead } from "core/request/AirbyteClient";
import { Action, Namespace, useAnalyticsService } from "core/services/analytics";

export const useAnalyticsTrackFunctions = (connectorType: "source" | "destination") => {
  const analytics = useAnalyticsService();

  const namespaceType = connectorType === "source" ? Namespace.SOURCE : Namespace.DESTINATION;

  const trackAction = useCallback(
    (
      connector: ConnectorDefinition | undefined,
      actionType: Action,
      actionDescription: string,
      jobInfo?: SynchronousJobRead | null,
      message?: string
    ) => {
      if (!connector) {
        return;
      }
      analytics.track(namespaceType, actionType, {
        actionDescription,
        connector: connector.name,
        connector_definition_id: Connector.id(connector),
        connector_documentation_url: connector.documentationUrl,
        external_message: jobInfo?.failureReason?.externalMessage || message,
        failure_reason: jobInfo?.failureReason,
      });
    },
    [analytics, namespaceType]
  );

  const trackTestConnectorStarted = useCallback(
    (connector: ConnectorDefinition | undefined) => {
      trackAction(connector, Action.TEST, "Test a connector");
    },
    [trackAction]
  );

  const trackTestConnectorSuccess = useCallback(
    (connector: ConnectorDefinition | undefined) => {
      trackAction(connector, Action.SUCCESS, "Tested connector - success");
    },
    [trackAction]
  );

  const trackTestConnectorFailure = useCallback(
    (connector: ConnectorDefinition | undefined, jobInfo: SynchronousJobRead | null, message: string) => {
      trackAction(connector, Action.FAILURE, "Tested connector - failure", jobInfo, message);
    },
    [trackAction]
  );
  return { trackTestConnectorStarted, trackTestConnectorSuccess, trackTestConnectorFailure };
};
