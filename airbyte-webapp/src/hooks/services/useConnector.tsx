import { useMutation } from "@tanstack/react-query";
import { useMemo } from "react";
import { FormattedMessage, useIntl } from "react-intl";

import { ConnectionConfiguration } from "area/connector/types";
import { useCurrentWorkspaceId } from "area/workspace/utils";
import { useConfig } from "config";
import {
  useGetOutOfDateConnectorsCount,
  useDestinationDefinitionList,
  useLatestDestinationDefinitionList,
  useUpdateDestinationDefinition,
  useLatestSourceDefinitionList,
  useSourceDefinitionList,
  useUpdateSourceDefinition,
} from "core/api";
import { CheckConnectionRead } from "core/api/types/AirbyteClient";
// eslint-disable-next-line import/no-restricted-paths
import { useRequestOptions } from "core/api/useRequestOptions";
import { DestinationService } from "core/domain/connector/DestinationService";
import { SourceService } from "core/domain/connector/SourceService";
import { useInitService } from "services/useInitService";

import { useAppMonitoringService } from "./AppMonitoringService";
import { useNotificationService } from "./Notification";

export const useUpdateAllConnectors = (connectorType: "sources" | "destinations") => {
  const { formatMessage } = useIntl();
  const workspaceId = useCurrentWorkspaceId();
  const { updateAllSourceVersions } = useUpdateSourceDefinitions();
  const { updateAllDestinationVersions } = useUpdateDestinationDefinitions();
  const { registerNotification } = useNotificationService();
  const { trackError } = useAppMonitoringService();

  return useMutation(
    ["updateAllConnectors", workspaceId],
    async () => (connectorType === "sources" ? updateAllSourceVersions() : updateAllDestinationVersions()),
    {
      onSuccess: (numberUpdated: number) => {
        registerNotification({
          id: `workspace.connectorUpdateSuccess.${connectorType}.${workspaceId}`,
          text: (
            <FormattedMessage
              id="admin.upgradeAll.success"
              values={{ count: numberUpdated, type: connectorType === "sources" ? "source" : "destination" }}
            />
          ),
          type: "success",
        });
      },
      onError: (error: Error) => {
        trackError(error);
        registerNotification({
          id: `workspace.connectorUpdateError.${connectorType}.${workspaceId}`,
          text:
            formatMessage({ id: "admin.upgradeAll.error" }, { type: connectorType }) +
            (error.message ? `: ${error.message}` : ""),
          type: "error",
        });
      },
    }
  );
};

export const useUpdateSourceDefinitions = () => {
  const { sourceDefinitions } = useSourceDefinitionList();
  const { sourceDefinitions: latestSourceDefinitions } = useLatestSourceDefinitionList();
  const { mutateAsync: updateSourceDefinition } = useUpdateSourceDefinition();

  const sourceDefinitionsWithUpdates = useMemo(
    () =>
      latestSourceDefinitions.filter((latestDefinition) => {
        const matchingExistingDefinition = sourceDefinitions.find(
          (definition) => definition.sourceDefinitionId === latestDefinition.sourceDefinitionId
        );
        return (
          matchingExistingDefinition !== undefined &&
          matchingExistingDefinition.dockerImageTag !== latestDefinition.dockerImageTag
        );
      }),
    [sourceDefinitions, latestSourceDefinitions]
  );

  const updateAllSourceVersions = async (): Promise<number> => {
    if (!sourceDefinitionsWithUpdates) {
      return 0;
    }

    await Promise.all(
      sourceDefinitionsWithUpdates?.map((item) =>
        updateSourceDefinition({
          sourceDefinitionId: item.sourceDefinitionId,
          dockerImageTag: item.dockerImageTag,
        })
      )
    );
    return sourceDefinitionsWithUpdates.length;
  };

  return { updateAllSourceVersions };
};

export const useUpdateDestinationDefinitions = () => {
  const { destinationDefinitions } = useDestinationDefinitionList();
  const { destinationDefinitions: latestDestinationDefinitions } = useLatestDestinationDefinitionList();
  const { mutateAsync: updateDestinationDefinition } = useUpdateDestinationDefinition();

  const newDestinationDefinitionsWithUpdates = useMemo(
    () =>
      latestDestinationDefinitions.filter((latestDefinition) => {
        const matchingExistingDefinition = destinationDefinitions.find(
          (definition) => definition.destinationDefinitionId === latestDefinition.destinationDefinitionId
        );
        return (
          matchingExistingDefinition !== undefined &&
          matchingExistingDefinition.dockerImageTag !== latestDefinition.dockerImageTag
        );
      }),
    [destinationDefinitions, latestDestinationDefinitions]
  );

  const updateAllDestinationVersions = async (): Promise<number> => {
    if (!newDestinationDefinitionsWithUpdates) {
      return 0;
    }
    await Promise.all(
      newDestinationDefinitionsWithUpdates?.map((item) =>
        updateDestinationDefinition({
          destinationDefinitionId: item.destinationDefinitionId,
          dockerImageTag: item.dockerImageTag,
        })
      )
    );
    return newDestinationDefinitionsWithUpdates.length;
  };

  return { updateAllDestinationVersions };
};

export const useGetConnectorsOutOfDate = () => {
  const { data: outOfDateConnectors } = useGetOutOfDateConnectorsCount();

  if (!outOfDateConnectors) {
    return {
      hasNewVersions: false,
      hasNewSourceVersion: false,
      hasNewDestinationVersion: false,
      countNewSourceVersion: 0,
      countNewDestinationVersion: 0,
      outOfDateConnectors: undefined,
    };
  }

  const hasNewSourceVersion = outOfDateConnectors?.sourceDefinitions > 0;
  const hasNewDestinationVersion = outOfDateConnectors?.destinationDefinitions > 0;
  const hasNewVersions = hasNewSourceVersion || hasNewDestinationVersion;

  return {
    hasNewVersions,
    hasNewSourceVersion,
    hasNewDestinationVersion,
    countNewSourceVersion: outOfDateConnectors.sourceDefinitions,
    countNewDestinationVersion: outOfDateConnectors.destinationDefinitions,
    outOfDateConnectors,
  };
};

function useGetDestinationService(): DestinationService {
  const { apiUrl } = useConfig();
  const requestOptions = useRequestOptions();

  return useInitService(() => new DestinationService(requestOptions), [apiUrl, requestOptions]);
}

function useGetSourceService(): SourceService {
  const { apiUrl } = useConfig();
  const requestOptions = useRequestOptions();

  return useInitService(() => new SourceService(requestOptions), [apiUrl, requestOptions]);
}

export type CheckConnectorParams = { signal: AbortSignal } & (
  | { selectedConnectorId: string }
  | {
      selectedConnectorId: string;
      name: string;
      connectionConfiguration: ConnectionConfiguration;
    }
  | {
      selectedConnectorDefinitionId: string;
      connectionConfiguration: ConnectionConfiguration;
      workspaceId: string;
    }
);

export const useCheckConnector = (formType: "source" | "destination") => {
  const destinationService = useGetDestinationService();
  const sourceService = useGetSourceService();

  return useMutation<CheckConnectionRead, Error, CheckConnectorParams>(async (params: CheckConnectorParams) => {
    const payload: Record<string, unknown> = {};

    if ("connectionConfiguration" in params) {
      payload.connectionConfiguration = params.connectionConfiguration;
    }

    if ("name" in params) {
      payload.name = params.name;
    }

    if ("workspaceId" in params) {
      payload.workspaceId = params.workspaceId;
    }

    if (formType === "destination") {
      if ("selectedConnectorId" in params) {
        payload.destinationId = params.selectedConnectorId;
      }

      if ("selectedConnectorDefinitionId" in params) {
        payload.destinationDefinitionId = params.selectedConnectorDefinitionId;
      }

      return await destinationService.check_connection(payload, {
        signal: params.signal,
      });
    }

    if ("selectedConnectorId" in params) {
      payload.sourceId = params.selectedConnectorId;
    }

    if ("selectedConnectorDefinitionId" in params) {
      payload.sourceDefinitionId = params.selectedConnectorDefinitionId;
    }

    return await sourceService.check_connection(payload, {
      signal: params.signal,
    });
  });
};
