import React from "react";
import PropTypes from "prop-types";
import { t } from "ttag";
import styled from "styled-components";
import Icon from "metabase/components/Icon";
import { SelectList } from "metabase/components/select-list";
import { color } from "metabase/lib/colors";
import { space } from "metabase/styled-components/theme";

export const DataBucketIcon = styled(Icon)`
  margin-top: 2px;
  color: ${color("text-dark")} !important;
`;

export const DataBucketDescription = styled.span`
  font-weight: bold;
  font-size: 12px;
`;

const BackButtonContainer = styled.div`
  display: flex;
  align-items: center;
  cursor: pointer;
`;

const BackButtonLabel = styled.span`
  font-size: 16px;
  color: ${color("text-dark")};

  margin-left: ${space(1)};

  overflow-wrap: anywhere;
  word-break: break-word;
  word-wrap: anywhere;

  :hover {
    color: ${color("brand")};
  }
`;

export function RawDataBackButton() {
  return (
    <BackButtonContainer>
      <Icon name="chevronleft" size={16} />
      <BackButtonLabel>{t`Raw Data`}</BackButtonLabel>
    </BackButtonContainer>
  );
}

export const DataBucketList = styled(SelectList)`
  width: 300px;
  padding: ${space(0)} ${space(1)} 12px ${space(1)};
`;

const DataBucketListItemIcon = styled(Icon)`
  color: ${color("text-dark")};
`;

const DataBucketTitleContainer = styled.div`
  display: flex;
  align-items: center;
`;

const DataBucketListItemTitle = styled.span`
  color: ${color("text-dark")};
  font-weight: 700;
  font-size: 14px;
  margin-left: ${space(1)};
`;

const DataBucketListItemDescriptionContainer = styled.div`
  margin-top: ${space(0)};
`;

const DataBucketListItemDescription = styled.span`
  color: ${color("text-light")};
  font-weight: 700;
  font-size: 12px;
`;

const DataBucketListItemContainer = styled(SelectList.BaseItem)`
  &:hover {
    ${DataBucketListItemIcon},
    ${DataBucketListItemTitle},
    ${DataBucketListItemDescription} {
      color: ${color("text-white")};
    }
  }
`;

DataBucketListItem.propTypes = {
  name: PropTypes.string.isRequired,
  icon: PropTypes.string.isRequired,
  description: PropTypes.string.isRequired,
};

export function DataBucketListItem(props) {
  const { name, icon, description } = props;
  return (
    <DataBucketListItemContainer {...props}>
      <DataBucketTitleContainer>
        <DataBucketListItemIcon name={icon} size={18} />
        <DataBucketListItemTitle>{name}</DataBucketListItemTitle>
      </DataBucketTitleContainer>
      <DataBucketListItemDescriptionContainer>
        <DataBucketListItemDescription>
          {description}
        </DataBucketListItemDescription>
      </DataBucketListItemDescriptionContainer>
    </DataBucketListItemContainer>
  );
}
