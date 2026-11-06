import fs from 'node:fs';

const source = 'docs/workflow-agent-system-architecture.md';
const target = 'docs/workflow-agent-system-architecture.joyspace.md';
const diagram = 'docs/diagrams/workflow-agent-four-layer.drawio';
const marker = '![Workflow与Agent协同的四层系统架构](diagrams/workflow-agent-four-layer.png)';
const markdown = fs.readFileSync(source, 'utf8');
if (markdown.split(marker).length !== 2) throw new Error('Expected exactly one architecture image');
const xml = fs.readFileSync(diagram, 'utf8').trim();
const metadata = JSON.stringify({ title: 'Workflow与Agent协同的四层系统架构', diagramType: 1 });
const content = markdown.replace(marker, `<!-- joyspace-diagram: ${metadata} -->\n\n\`\`\`drawio\n${xml}\n\`\`\``);
if (content.includes('\uFFFD')) throw new Error('Invalid text encoding');
const previous = fs.existsSync(target) ? fs.readFileSync(target, 'utf8') : null;
let patch = '*** Begin Patch\n';
if (previous !== content) {
  patch += previous === null ? `*** Add File: ${target}\n` : `*** Update File: ${target}\n@@\n${previous.trimEnd().split('\n').map(line => '-' + line).join('\n')}\n`;
  patch += content.trimEnd().split('\n').map(line => '+' + line).join('\n') + '\n';
}
process.stdout.write(patch + '*** End Patch\n');
