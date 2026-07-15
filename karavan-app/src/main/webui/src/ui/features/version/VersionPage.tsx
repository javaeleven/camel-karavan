import React from 'react';
import {Card, CardBody, CardTitle, Content, Label, Stack, StackItem} from "@patternfly/react-core";
import {RightPanel} from "@shared/ui/RightPanel";
// Static changelog. Add a new entry at the TOP for each deploy; the first entry
// is treated as the current version.
import versionHistory from "../../../version.json";

interface VersionEntry {
    version: string;
    description: string;
}

export const VersionPage = () => {

    const history = versionHistory as VersionEntry[];
    const current = history[0]?.version ?? "unknown";

    function title() {
        return <Content>
            <Content component="h2">Version</Content>
        </Content>;
    }

    return (
        <RightPanel
            title={title()}
            mainPanel={
                <div style={{padding: '16px', overflowY: 'auto'}}>
                    <Content>
                        <Content component="p">
                            Current version: <Label color="blue">{current}</Label>
                        </Content>
                    </Content>
                    <Stack hasGutter style={{marginTop: '16px'}}>
                        {history.map((entry, index) => (
                            <StackItem key={index}>
                                <Card isCompact>
                                    <CardTitle>{entry.version}</CardTitle>
                                    <CardBody>{entry.description}</CardBody>
                                </Card>
                            </StackItem>
                        ))}
                    </Stack>
                </div>
            }
        />
    );
};
