import fs from 'fs';

const path = process.argv[2];
const old = JSON.parse(fs.readFileSync(path, 'utf8'));
const rowsByTier = {
  RARE: ['RARE', 'NORMAL', 'FREQUENT'],
  NORMAL: ['NORMAL', 'FREQUENT'],
  FREQUENT: ['FREQUENT']
};
const stateOf = { whitelist: 'emphasis', neutral: 'normal', blacklist: 'weakened' };

const kinds = {};
for (const [kind, tier] of Object.entries(old.kinds)) {
  const declared = String(tier).toUpperCase();
  const state = stateOf[String((old.list || {})[kind] || 'neutral')] || 'normal';
  const states = {};
  for (const row of rowsByTier[declared] || ['FREQUENT']) {
    states[row] = state;
  }
  kinds[kind] = { tier: declared, states };
}

const next = { schemaVersion: 5, mapping: 'anti-diagonal', kinds };
fs.writeFileSync(path, JSON.stringify(next, null, 2) + '\n', 'utf8');
console.log('migrated', Object.keys(kinds).length, 'kinds');