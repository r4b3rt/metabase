/* eslint-disable react/prop-types */
import React, { useState, useMemo, useCallback } from "react";
import { Box } from "grid-styled";
import _ from "underscore";
import { withRouter } from "react-router";

import Collection from "metabase/entities/collections";
import Search from "metabase/entities/search";

import listSelect from "metabase/hoc/ListSelect";

import BulkActions from "metabase/collections/components/BulkActions";
import Header from "metabase/collections/components/Header";
import ItemList from "metabase/collections/components/ItemList";
import PinnedItems from "metabase/collections/components/PinnedItems";

import ItemsDragLayer from "metabase/containers/dnd/ItemsDragLayer";
import PaginationControls from "metabase/components/PaginationControls";

import { usePagination } from "metabase/hooks/use-pagination";

const PAGE_SIZE = 25;

const MIN_ITEMS_TO_SHOW_FILTERS = 5;

const allModels = ["dashboard", "card", "snippet", "pulse"];

const getModelsByFilter = filter => {
  if (!filter) {
    return allModels;
  }

  return [filter];
};

function CollectionContent({
  onSelectNone,
  onSelectAll,

  collection,
  collectionId,

  isAdmin,
  isRoot,
  selected,
  deselected,
  selection,
  onToggleSelected,
  location,
  scrollElement,
  router,
}) {
  const [selectedItems, setSelectedItems] = useState(null);
  const [selectedAction, setSelectedAction] = useState(null);
  const { handleNextPage, handlePreviousPage, setPage, page } = usePagination();
  const [filter, setFilter] = useState(location.query.type);

  const handleBulkArchive = async () => {
    try {
      await Promise.all(selected.map(item => item.setArchived(true)));
    } finally {
      handleBulkActionSuccess();
    }
  };

  const handleBulkMoveStart = () => {
    setSelectedItems(selected);
    setSelectedAction("move");
  };

  const handleBulkMove = async collection => {
    try {
      await Promise.all(
        selectedItems.map(item => item.setCollection(collection)),
      );
      handleCloseModal();
    } finally {
      handleBulkActionSuccess();
    }
  };

  const handleBulkActionSuccess = () => {
    // Clear the selection in listSelect
    // Fixes an issue where things were staying selected when moving between
    // different collection pages
    onSelectNone();
  };

  const handleCloseModal = () => {
    setSelectedItems(null);
    setSelectedAction(null);
  };

  const handleFilterChange = type => {
    router.push({
      pathname: location.pathname,
      search: type ? ("?" + new URLSearchParams({ type }).toString()) : null,
    });

    setFilter(type);
    setPage(0);
  };

  const unpinnedQuery = {
    collection: collectionId,
    models: getModelsByFilter(filter),
    limit: PAGE_SIZE,
    offset: PAGE_SIZE * page,
    pinned_state: "is_not_pinned",
  };

  const pinnedQuery = {
    collection: collectionId,
    pinned_state: "is_pinned",
  };

  return (
    <Search.ListLoader query={unpinnedQuery} wrapped>
      {({ list: unpinnedItems, metadata }) => {
        const hasItems = unpinnedItems.length > 0;
        const hideFilters =
          !filter && unpinnedItems.length < MIN_ITEMS_TO_SHOW_FILTERS;

        return (
          <Search.ListLoader query={pinnedQuery} wrapped>
            {({ list: pinnedItems }) => {
              const sortedPinnedItems = pinnedItems.sort(
                (a, b) => a.collection_position - b.collection_position,
              );

              return (
                <Box pt={2}>
                  <Box w={"80%"} ml="auto" mr="auto">
                    <Header
                      isRoot={isRoot}
                      isAdmin={isAdmin}
                      collectionId={collectionId}
                      showFilters={!hideFilters}
                      collectionHasPins={pinnedItems.length > 0}
                      collection={collection}
                      unpinnedItems={unpinnedItems}
                    />

                    <PinnedItems
                      items={sortedPinnedItems}
                      collection={collection}
                      onMove={selectedItems => {
                        setSelectedItems(selectedItems);
                        setSelectedAction("move");
                      }}
                      onCopy={selectedItems => {
                        setSelectedItems(selectedItems);
                        setSelectedAction("copy");
                      }}
                    />

                    <ItemList
                      filter={filter}
                      scrollElement={scrollElement}
                      items={unpinnedItems}
                      empty={unpinnedItems.length === 0}
                      showFilters={!hideFilters}
                      selection={selection}
                      collection={collection}
                      onToggleSelected={onToggleSelected}
                      collectionHasPins={pinnedItems.length > 0}
                      onFilterChange={handleFilterChange}
                      onMove={selectedItems => {
                        setSelectedItems(selectedItems);
                        setSelectedAction("move");
                      }}
                      onCopy={selectedItems => {
                        setSelectedItems(selectedItems);
                        setSelectedAction("copy");
                      }}
                    />
                    <div className="flex justify-end my3">
                      {hasItems && (
                        <PaginationControls
                          showTotal
                          page={page}
                          pageSize={PAGE_SIZE}
                          total={metadata.total}
                          itemsLength={unpinnedItems.length}
                          onNextPage={handleNextPage}
                          onPreviousPage={handlePreviousPage}
                        />
                      )}
                    </div>
                  </Box>
                  <BulkActions
                    selected={selected}
                    onSelectAll={onSelectAll}
                    onSelectNone={onSelectNone}
                    handleBulkArchive={handleBulkArchive}
                    handleBulkMoveStart={handleBulkMoveStart}
                    handleBulkMove={handleBulkMove}
                    handleCloseModal={handleCloseModal}
                    deselected={deselected}
                    selectedItems={selectedItems}
                    selectedAction={selectedAction}
                  />
                  <ItemsDragLayer selected={selected} />
                </Box>
              );
            }}
          </Search.ListLoader>
        );
      }}
    </Search.ListLoader>
  );
}

export default _.compose(
  Collection.load({
    id: (_, props) => props.collectionId,
    reload: true,
  }),
  listSelect({
    listProp: "unpinned",
    keyForItem: item => `${item.model}:${item.id}`,
  }),
  withRouter,
)(CollectionContent);
