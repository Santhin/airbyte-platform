import { FormattedMessage } from "react-intl";

import { CollapsibleCard } from "components/ui/CollapsibleCard";

import { useConnectionFormService } from "hooks/services/ConnectionForm/ConnectionFormService";

import { ConnectionConfigurationHookFormPreview } from "./ConnectionConfigurationHookFormPreview";
import { DestinationStreamPrefixNameHookForm } from "./DestinationStreamPrefixNameHookForm";
import { NamespaceDefinitionHookFormField } from "./NamespaceDefinitionHookFormField";
import { NonBreakingChangesPreferenceHookFormField } from "./NonBreakingChangesPreferenceHookFormField";
import { ScheduleHookFormField } from "./ScheduleHookFormField/ScheduleHookFormField";

/**
 * react-hook-form version of ConnectionFormFields
 * this component is used in create and update connection cases
 */
export const ConnectionConfigurationHookFormCard = () => {
  const { mode } = useConnectionFormService();
  const isEditMode = mode === "edit";

  return (
    <CollapsibleCard
      title={<FormattedMessage id="form.configuration" />}
      collapsible={isEditMode}
      defaultCollapsedState={isEditMode}
      collapsedPreviewInfo={<ConnectionConfigurationHookFormPreview />}
      testId="configuration"
    >
      <ScheduleHookFormField />
      <NamespaceDefinitionHookFormField />
      <DestinationStreamPrefixNameHookForm />
      <NonBreakingChangesPreferenceHookFormField />
    </CollapsibleCard>
  );
};
