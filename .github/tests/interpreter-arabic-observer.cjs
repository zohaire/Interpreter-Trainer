const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

// Model DOM mutation delivery explicitly so a self-triggering observer fails quickly instead
// of hanging Node's own event loop. Like the DOM, assigning the same text still queues a record.
let observer;
let pending = false;
let writes = 0;
const options = [];
function option(value, label, inCall = false) {
  let text = label;
  const node = {
    value,
    get textContent() { return text; },
    set textContent(next) {
      text = next;
      writes += 1;
      if (observer) pending = true;
    },
    closest: selector => inCall && selector === '#callVoiceLang' ? {} : null
  };
  options.push(node);
  return node;
}
const composer = option('ar-MA', 'AR');
const call = option('ar-MA', 'العربية', true);
const english = option('en-US', 'English');
const window = { puter: { ai: { chat() {} } } };
const context = {
  window,
  document: { documentElement: {}, querySelectorAll: () => options },
  MutationObserver: class {
    constructor(callback) { this.callback = callback; }
    observe() { observer = this.callback; }
  },
  setInterval() { throw new Error('The mock SDK is already loaded.'); },
  clearInterval() {}
};
vm.runInNewContext(fs.readFileSync('app/src/main/assets/interpreter_standard_arabic.js', 'utf8'), context);
assert.equal(composer.textContent, 'AR · MSA');
assert.equal(call.textContent, 'العربية الفصحى');
assert.equal(english.textContent, 'English');

function flushMutations() {
  let deliveries = 0;
  while (pending && deliveries < 10) {
    pending = false;
    observer();
    deliveries += 1;
  }
  assert.equal(pending, false, 'Arabic relabeling endlessly schedules its own MutationObserver and freezes the AI page.');
}

// A status update, streamed token, or newly inserted voice control starts the old loop.
pending = true;
flushMutations();
assert.equal(writes, 2, 'Already correct Arabic labels must not mutate the DOM.');

const lateOption = option('ar-SA', 'Arabic', true);
pending = true;
flushMutations();
assert.equal(lateOption.textContent, 'العربية الفصحى');
assert.equal(writes, 3);
composer.textContent = 'Arabic';
flushMutations();
assert.equal(composer.textContent, 'AR · MSA');
assert.equal(writes, 5);
console.log('Arabic labels update correctly and mutation delivery settles without starving the event loop.');
