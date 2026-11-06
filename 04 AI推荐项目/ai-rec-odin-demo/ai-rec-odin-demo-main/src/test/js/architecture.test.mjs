import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';

const repo = new URL('../../../../', import.meta.url);
const app = new URL('ai-rec-odin-demo-app/src/main/java/com/jd/diviner/ai/rec/odin/demo/app/', repo);

async function sources(directory) {
  const result = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const file = new URL(entry.name + (entry.isDirectory() ? '/' : ''), directory);
    if (entry.isDirectory()) result.push(...await sources(file));
    else if (entry.name.endsWith('.java')) result.push({ file, text: await readFile(file, 'utf8') });
  }
  return result;
}

test('reusable agent components and domain rules never depend on execution, tools or HTTP', async () => {
  for (const layer of ['agent', 'domain', 'infrastructure', 'support']) {
    for (const { file, text } of await sources(new URL(layer + '/', app))) {
      assert.doesNotMatch(text, /com\.jd\.diviner\.ai\.rec\.odin\.demo\.(?:web\.|app\.(?:application|tools|bootstrap)\.)/, file.pathname);
      if (layer === 'domain') assert.doesNotMatch(text, /com\.jd\.diviner\.ai\.rec\.odin\.demo\.app\.(?:agent|infrastructure)\./, file.pathname);
    }
  }
});

test('all Java packages match their directories and no production class uses removed packages', async () => {
  for (const { file, text } of await sources(app)) {
    const declaredPackage = text.match(/^package ([^;]+);/m)[1];
    assert.ok(file.pathname.endsWith(declaredPackage.replaceAll('.', '/') + '/' + file.pathname.split('/').at(-1)), file.pathname);
    assert.doesNotMatch(text, /com\.jd\.diviner\.ai\.rec\.odin\.demo\.app\.(?:skill|rag|config|diagnostics|mcp|service)\./);
  }
});

test('the common Skill engine does not select shopping workflows or validate business entities', async () => {
  const engine = await readFile(new URL('application/skill/SkillEngine.java', app), 'utf8');
  assert.doesNotMatch(engine, /com\.jd\.diviner\.ai\.rec\.odin\.demo\.app\.(?:domain|tools|application\.commerce)\./);
  assert.doesNotMatch(engine, /preparedRecommendation\(|deepRecommendation\(|deepWorkflow\(|shopping-recommendation|retrieve_product_knowledge|get_user_profile/);
  assert.match(engine, /catalog\.extension\(skill\)\.create\(context\)/);
  assert.match(engine, /executor\.nextStep\(context\)/);
  assert.match(engine, /behavior\.finish\(output\)/);
});

test('only product knowledge is registered and only the workspace starts', async () => {
  const config = await readFile(new URL('bootstrap/RagConfiguration.java', app), 'utf8');
  assert.match(config, /@Bean\s+public RagKnowledgeBase productRagKnowledgeBase\(/);
  assert.doesNotMatch(config, /public RagKnowledgeBase ragKnowledgeBase\(/);
  const main = await readFile(new URL('ai-rec-odin-demo-main/src/main/java/com/jd/diviner/ai/rec/odin/demo/AiRecOdinDemoApplication.java', repo), 'utf8');
  assert.match(main, /ChatController.class/);
  assert.match(main, /SkillController.class/);
  assert.doesNotMatch(main, /(?:OrderController|RagController|DongBoot)/);
  const pom = await readFile(new URL('pom.xml', repo), 'utf8');
  assert.doesNotMatch(pom, /ai-rec-odin-demo-client/);
});
