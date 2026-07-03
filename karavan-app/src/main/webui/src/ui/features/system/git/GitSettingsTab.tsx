/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import {useEffect, useState} from 'react';
import {
    Button,
    Form,
    FormGroup,
    FormHelperText,
    HelperText,
    HelperTextItem,
    TextInput
} from '@patternfly/react-core';
import {SystemApi} from "@api/SystemApi";
import {EventBus} from "@features/project/designer/utils/EventBus";

/**
 * Per-user Git credentials. These authenticate the per-project Git operations
 * (fetching branches when configuring a project, and clone/push/pull). The token
 * is write-only: the backend never returns it, so the field is left blank and an
 * empty value on save keeps the previously stored token.
 */
export function GitSettingsTab() {

    const [gitUsername, setGitUsername] = useState<string>('');
    const [gitToken, setGitToken] = useState<string>('');
    const [hasToken, setHasToken] = useState<boolean>(false);
    const [saving, setSaving] = useState<boolean>(false);

    useEffect(() => {
        SystemApi.getGitConfig(config => {
            setGitUsername(config.gitUsername ?? '');
            setHasToken(config.hasToken);
        });
    }, []);

    function save() {
        setSaving(true);
        SystemApi.saveGitConfig(gitUsername, gitToken, config => {
            setHasToken(config.hasToken);
            setGitToken('');
            setSaving(false);
            EventBus.sendAlert('Success', 'Git credentials saved', "success");
        });
    }

    return (
        <div className="right-panel-card" style={{maxWidth: '600px', padding: '16px'}}>
            <Form isHorizontal>
                <FormGroup label="Git username" fieldId="gitUsername">
                    <TextInput
                        id="gitUsername"
                        value={gitUsername}
                        autoComplete="off"
                        onChange={(_e, v) => setGitUsername(v)}
                    />
                </FormGroup>
                <FormGroup label="Git token / password" fieldId="gitToken">
                    <TextInput
                        id="gitToken"
                        type="password"
                        value={gitToken}
                        autoComplete="new-password"
                        placeholder={hasToken ? '•••••••• (leave blank to keep current)' : ''}
                        onChange={(_e, v) => setGitToken(v)}
                    />
                    <FormHelperText>
                        <HelperText>
                            <HelperTextItem>
                                A personal access token (PAT) is recommended. Used only to authenticate
                                your Git remotes; it is stored server-side and never shown again.
                            </HelperTextItem>
                        </HelperText>
                    </FormHelperText>
                </FormGroup>
                <Button variant="primary" onClick={save} isLoading={saving} isDisabled={saving} style={{width: 'fit-content'}}>
                    Save
                </Button>
            </Form>
        </div>
    )
}
