import fs from 'node:fs';

const source = 'docs/agent-architecture-three-views.md';
const target = 'docs/agent-architecture-three-views.joyspace.md';
let count = 0;
const content = fs.readFileSync(source, 'utf8').replace(/!\[(图[123] [^\]]+)\]\(diagrams\/(agent-three-views-[a-z]+)\.png\)/g, (match, title, name) => {
  count += 1;
  const xml = fs.readFileSync(`docs/diagrams/${name}.drawio`, 'utf8').trim();
  return `<!-- joyspace-diagram: ${JSON.stringify({ title, diagramType: 1 })} -->\n\n\`\`\`drawio\n${xml}\n\`\`\``;
});
if (count !== 3 || (content.match(/```drawio/g) || []).length !== 3) throw new Error('Expected exactly three diagrams');
if (/02A|02B|\uFFFD/.test(content)) throw new Error('Stale diagram references or invalid encoding');
const previous = fs.existsSync(target) ? fs.readFileSync(target, 'utf8') : null;
let patch = '*** Begin Patch\n';
if (previous !== content) {
  patch += previous === null ? `*** Add File: ${target}\n` : `*** Update File: ${target}\n@@\n${previous.trimEnd().split('\n').map(line => '-' + line).join('\n')}\n`;
  patch += content.trimEnd().split('\n').map(line => '+' + line).join('\n') + '\n';
}
process.stdout.write(patch + '*** End Patch\n');
