// 投放 (Targeting) tab. In the original CampaignsPage this tab rendered the
// standalone KeywordsPage, which already owns its own table, filters, and bulk
// actions. To preserve behavior exactly, the extracted tab delegates to it.

import { KeywordsPage } from '../../KeywordsPage';

export function TargetingTab() {
  return <KeywordsPage />;
}

export default TargetingTab;
