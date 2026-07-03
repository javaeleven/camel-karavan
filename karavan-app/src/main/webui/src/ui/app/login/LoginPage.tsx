import React from 'react';
import {Button, Card, CardBody, CardFooter, CardHeader, Content} from '@patternfly/react-core';
import './LoginPage.css'
import {CamelIcon, KaravanIcon} from "@features/project/designer/icons/KaravanIcons";
import {SvgIcon} from "@shared/icons/SvgIcon";
import jibLogo from '@shared/icons/jib.png';
import {PlatformVersion} from "@shared/ui/PlatformLogos";
import PlatformLogo from "@app/navigation/PlatformLogo";
import OrbitLines from "@app/login/OrbitLines";
import {useReadinessStore} from "@stores/ReadinessStore";

export const LoginPage: React.FunctionComponent = () => {

    const { readiness } = useReadinessStore();

    // Authentication is SSO-only (OIDC via the Backend-for-Frontend flow). We do
    // NOT auto-redirect to the IdP on navigation — the page renders a
    // "Sign in with SSO" button and only that click starts the server-side login.


    function getLogos() {
        return [
            <div className="powered-by-logo counter-rotator">
                <a href="https://github.com/apache/camel-karavan" target="_blank">{KaravanIcon("logo")}</a>
            </div>,
            <div className="powered-by-logo counter-rotator">
               <a href="https://groovy-lang.org/" target="_blank">{SvgIcon({icon: 'groovy', className: 'groovy-logo'})}</a>
            </div>,
            <div className="powered-by-logo counter-rotator">
                <a href="https://github.com/GoogleContainerTools/jib" target="_blank">
                    <img src={jibLogo} alt="Logo" className="jib-logo"/>
                </a>
            </div>,
            <div className="powered-by-logo counter-rotator">
                <a href="https://camel.apache.org/" target="_blank">{CamelIcon()}</a>
            </div>,
            <div className="powered-by-logo counter-rotator">
                <a href="https://www.patternfly.org/" target={'_blank'}>
                    {SvgIcon({icon: 'patternfly', className: 'patternfly-logo'})}
                </a>
            </div>,
            <div className="powered-by-logo counter-rotator">
                <a href="https://eclipse.dev/jkube/" target="_blank">
                    {SvgIcon({icon: 'jkube', className: 'jkube-logo'})}
                </a>
            </div>,
            <div className="powered-by-logo counter-rotator">
                <a href="https://www.keycloak.org/" target="_blank">
                    {SvgIcon({icon: 'keycloak', className: 'patternfly-logo'})}
                </a>
            </div>
        ];
    }

    const LOGOS = getLogos();


    function getRightSide() {
        return (
            <div className="karavan-form-panel dark-form">
                <div className="form-wrapper">
                    <Card className="login" isLarge>
                        <CardHeader>
                            <div style={{display: "flex", flexDirection: 'row', justifyContent: 'space-between', alignItems: "center"}}>
                                <Content component='h3' className="login-header">Single Sign-On</Content>
                                <PlatformVersion environment={readiness?.environment}/>
                            </div>
                        </CardHeader>
                        <CardBody>
                            <Content component="p">Sign in with your organization account to continue.</Content>
                        </CardBody>
                        <CardFooter style={{ textAlign: "center" }}>
                            <Button variant="primary" onClick={() => window.location.assign('/auth/login')}>
                                Sign in with SSO
                            </Button>
                        </CardFooter>
                    </Card>
                </div>
            </div>
        )
    }

    function getLeftSide() {
        return (
            <div className="karavan-brand-panel">
                <div className="brand-content">
                    <div className="brand-name">
                        <div>
                            <div className="tagline1 gradient-text-blue">Apache</div>
                            <div className="tagline1 gradient-text-blue-gold">Camel</div>
                            <div className="tagline1 gradient-text-gold">Karavan</div>
                        </div>
                    </div>
                </div>
                <div className="solar-content">
                    <div className="solar-system">
                        <OrbitLines />
                        <div className="static-sun">
                            <a href="https://camel.apache.org/" target="_blank">
                                {PlatformLogo("logo")}
                            </a>
                        </div>
                        <div className="orbit-ring">
                            {LOGOS.map((logo, index) => {
                                const total = LOGOS.length;
                                const angle = (360 / total) * index;
                                // Increased radius slightly to make room for the center logo
                                const radius = 150;
                                const style = {'--angle': `${angle}deg`, '--radius': `${radius}px`,} as React.CSSProperties;
                                return (
                                    <div key={index} className="orbit-item" style={style}>
                                        {logo}
                                    </div>
                                );
                            })}
                        </div>
                    </div>
                </div>
            </div>
        )
    }

    return (
        <div className="karavan-container">
            {getLeftSide()}
            {getRightSide()}
        </div>
    )
}