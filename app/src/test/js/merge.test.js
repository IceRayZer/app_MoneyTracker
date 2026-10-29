// Test de mergeData (synchro entre téléphones). Lance : node app/src/test/js/merge.test.js
// La fonction vit dans index.html, entre les repères « <merge> » et « </merge> » : on l'extrait telle quelle.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const html = fs.readFileSync(path.join(__dirname, '../../main/assets/index.html'), 'utf8');
const block = html.match(/\/\/ <merge>([\s\S]*?)\/\/ <\/merge>/)[1];
const { mergeData, TOMB } = new Function(block + '; return { mergeData, TOMB };')();

const empty = () => ({ accounts: [], expenses: [], withdrawals: [], savings: 0, savingsAt: 0, sourceAccounts: {}, deleted: {} });
const acc = (id, name, t = 1, balance = 100) => ({ id, name, balance, tag: '', updatedAt: t });
const exp = (id, accountId, t = 1, amount = 10) => ({ id, name: 'e' + id, amount, type: 'depense', accountId, tag: '', updatedAt: t });
const wd = (id, accountId, t = 1, extra = {}) => ({ id, name: 'w' + id, amount: 5, accountId, date: '2026-10-29', updatedAt: t, ...extra });
const mk = o => ({ ...empty(), ...o });

// Même résultat quel que soit le téléphone qui fusionne (l'ordre des listes et sourceAccounts mis à part).
const byId = l => [...l].sort((x, y) => x.id - y.id);
const canon = d => ({ accounts: byId(d.accounts), expenses: byId(d.expenses), withdrawals: byId(d.withdrawals), savings: d.savings, savingsAt: d.savingsAt, deleted: d.deleted });
function both(a, b) {
    const ab = mergeData(a, b), ba = mergeData(b, a);
    assert.deepEqual(canon(ab.data), canon(ba.data), 'les deux téléphones doivent obtenir la même chose');
    // Refusionner ne change plus rien (idempotence)
    assert.deepEqual(canon(mergeData(ab.data, b).data), canon(ab.data));
    return ab;
}

// 1. Ajouts de chaque côté : tout est conservé
{
    const a = mk({ accounts: [acc(1, 'N26')], withdrawals: [wd(10, 1)] });
    const b = mk({ accounts: [acc(1, 'N26'), acc(2, 'Livret A')], withdrawals: [wd(11, 2)] });
    const { data, stats } = both(a, b);
    assert.equal(data.accounts.length, 2);
    assert.equal(data.withdrawals.length, 2);
    assert.deepEqual(stats, { added: 2, changed: 0, removed: 0 }); // vu depuis a : Livret A + w11
}

// 2. Même élément modifié des deux côtés : le plus récent gagne
{
    const a = mk({ accounts: [acc(1, 'N26', 5, 100)] });
    const b = mk({ accounts: [acc(1, 'N26', 9, 250)] });
    assert.equal(both(a, b).data.accounts[0].balance, 250);
}

// 3. Suppression : propagée, et l'élément ne revient pas
{
    const a = mk({ accounts: [acc(1, 'N26')], withdrawals: [], deleted: { 'w:10': 50 } });
    const b = mk({ accounts: [acc(1, 'N26')], withdrawals: [wd(10, 1, 20)] });
    const r = both(a, b);
    assert.equal(r.data.withdrawals.length, 0);
    assert.equal(r.data.deleted['w:10'], 50);
    // ... sauf si l'autre téléphone l'a modifié APRÈS la suppression
    const b2 = mk({ accounts: [acc(1, 'N26')], withdrawals: [wd(10, 1, 80)] });
    assert.equal(both(a, b2).data.withdrawals.length, 1);
}

// 4. Même compte créé séparément sur chaque téléphone : un seul compte, le plus petit id, entrées rattachées
{
    const a = mk({ accounts: [acc(300, 'N26', 3, 100)], expenses: [exp(301, 300)] });
    const b = mk({ accounts: [acc(200, 'n26 ', 4, 150)], expenses: [exp(201, 200)], withdrawals: [wd(202, 200)] });
    const r = both(a, b);
    assert.equal(r.data.accounts.length, 1);
    assert.equal(r.data.accounts[0].id, 200);
    assert.equal(r.data.accounts[0].balance, 150); // le plus récent
    assert.ok(r.data.expenses.every(e => e.accountId === 200) && r.data.withdrawals.every(w => w.accountId === 200));
    assert.equal(r.data.expenses.length, 2);
}

// 5. Anciennes données (sans updatedAt ni deleted) : rien ne se perd
{
    const old = { accounts: [{ id: 1, name: 'N26', balance: 10, tag: '' }], expenses: [], withdrawals: [{ id: 5, name: 'x', amount: 3, accountId: 1, date: '2026-01-01' }], savings: 50 };
    const r = both(old, mk({ accounts: [acc(1, 'N26', 7, 10)] }));
    assert.equal(r.data.withdrawals.length, 1);
    assert.equal(r.data.savings, 50);
}

// 6. Épargne : la plus récente gagne
{
    const r = both(mk({ savings: 100, savingsAt: 5 }), mk({ savings: 300, savingsAt: 9 }));
    assert.equal(r.data.savings, 300);
}

// 7. Suppression d'un compte avec ses entrées (pierres tombales en cascade)
{
    const a = mk({ deleted: { 'a:1': 40, 'e:7': 40 } });
    const b = mk({ accounts: [acc(1, 'N26', 10)], expenses: [exp(7, 1, 10)] });
    const r = both(a, b);
    assert.equal(r.data.accounts.length + r.data.expenses.length, 0);
}

// 8. Les données de l'autre téléphone ne sont jamais modifiées (copie)
{
    const b = mk({ accounts: [acc(2, 'X')] }), snap = JSON.stringify(b);
    mergeData(mk({ accounts: [acc(3, 'x')] }), b);
    assert.equal(JSON.stringify(b), snap);
}

console.log('merge: 8 scénarios OK');
