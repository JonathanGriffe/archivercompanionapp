//! Does rustls accept the companion app's self-signed certificate as a trust
//! anchor? Replicates `phone.rs::pinned` and `Phone::request` exactly.
use std::net::{SocketAddr, ToSocketAddrs};

#[derive(Debug)]
struct Fixed(Vec<SocketAddr>);

impl ureq::unversioned::resolver::Resolver for Fixed {
    fn resolve(
        &self,
        _uri: &ureq::http::Uri,
        _config: &ureq::config::Config,
        _timeout: ureq::unversioned::transport::NextTimeout,
    ) -> Result<ureq::unversioned::resolver::ResolvedSocketAddrs, ureq::Error> {
        let mut out = ureq::unversioned::resolver::ResolvedSocketAddrs::from_fn(|_| {
            SocketAddr::from(([0, 0, 0, 0], 0))
        });
        for addr in self.0.iter().take(8) {
            out.push(*addr);
        }
        Ok(out)
    }
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let pem_path = &args[1];
    let name = &args[2];
    let address = &args[3];
    let token = &args[4];

    let pem = std::fs::read(pem_path).expect("read pem");
    let cert = match ureq::tls::Certificate::from_pem(&pem) {
        Ok(c) => c,
        Err(e) => { println!("FAIL parse-pem {e}"); std::process::exit(1); }
    };

    let config = ureq::config::Config::builder()
        .tls_config(
            ureq::tls::TlsConfig::builder()
                .root_certs(ureq::tls::RootCerts::new_with_certs(&[cert]))
                .build(),
        )
        .build();

    let to: Vec<SocketAddr> = address.to_socket_addrs().unwrap().collect();
    let agent = ureq::Agent::with_parts(
        config,
        ureq::unversioned::transport::DefaultConnector::default(),
        Fixed(to),
    );

    let port = address.rsplit_once(':').map(|(_, p)| p).unwrap();
    let url = format!("https://{name}:{port}/v1/items?limit=200");
    let result = agent
        .get(&url)
        .config()
        .http_status_as_error(false)
        .build()
        .header("Authorization", format!("Bearer {token}"))
        .call();

    match result {
        Ok(mut r) => {
            let status = r.status().as_u16();
            let body = r.body_mut().read_to_string().unwrap_or_default();
            println!("OK status={status} body={body}");
        }
        Err(e) => {
            println!("FAIL request {e}");
            std::process::exit(1);
        }
    }
}
