// 搜索词 (Search Terms) tab. In the original CampaignsPage this tab rendered the
// standalone SearchTermsPage, which already owns its own table and filters. To
// preserve behavior exactly, the extracted tab delegates to it.

import { SearchTermsPage } from '../../SearchTermsPage';

export function SearchTermsTab() {
  return <SearchTermsPage />;
}

export default SearchTermsTab;
