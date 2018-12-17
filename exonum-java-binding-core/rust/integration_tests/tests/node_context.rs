extern crate futures;
extern crate integration_tests;
extern crate java_bindings;
#[macro_use]
extern crate lazy_static;
extern crate failure;

use std::sync::Arc;

use futures::{
    sync::mpsc::{self, Receiver},
    Stream,
};
use integration_tests::vm::create_vm_for_tests_with_fake_classes;
use java_bindings::{
    exonum::{
        blockchain::{Blockchain, Service, Transaction},
        crypto::{gen_keypair, Hash, PublicKey, SecretKey},
        messages::{RawTransaction, ServiceTransaction},
        node::{ApiSender, ExternalMessage},
        storage::{MemoryDB, Snapshot},
    },
    jni::JavaVM,
    MainExecutor, NodeContext,
};

lazy_static! {
    static ref VM: Arc<JavaVM> = create_vm_for_tests_with_fake_classes();
    pub static ref EXECUTOR: MainExecutor = MainExecutor::new(VM.clone());
}

#[test]
fn submit_transaction() {
    let keypair = gen_keypair();
    let (node, app_rx) = create_node_with_keypair(keypair.0, keypair.1);
    let service_id = 0;
    let service_transaction = ServiceTransaction::from_raw_unchecked(0, vec![1, 2, 3]);
    let raw_transaction = RawTransaction::new(service_id, service_transaction);
    node.submit(raw_transaction.clone()).unwrap();
    let sent_message = app_rx.wait().next().unwrap().unwrap();
    match sent_message {
        ExternalMessage::Transaction(sent) => {
            let tx_payload = sent.payload();
            let tx_author = sent.author();
            assert_eq!(&raw_transaction, tx_payload);
            assert_eq!(tx_author, keypair.0);
        }
        _ => panic!("Message is not Transaction"),
    }
}

fn create_node() -> (NodeContext, Receiver<ExternalMessage>) {
    let service_keypair = gen_keypair();
    create_node_with_keypair(service_keypair.0, service_keypair.1)
}

fn create_node_with_keypair(
    public_key: PublicKey,
    secret_key: SecretKey,
) -> (NodeContext, Receiver<ExternalMessage>) {
    let api_channel = mpsc::channel(128);
    let (app_tx, app_rx) = (ApiSender::new(api_channel.0), api_channel.1);

    struct EmptyService;

    impl Service for EmptyService {
        fn service_id(&self) -> u16 {
            0
        }

        fn service_name(&self) -> &str {
            "empty_service"
        }

        fn state_hash(&self, _: &Snapshot) -> Vec<Hash> {
            vec![]
        }

        fn tx_from_raw(&self, _: RawTransaction) -> Result<Box<dyn Transaction>, failure::Error> {
            unimplemented!()
        }
    }

    let storage = MemoryDB::new();
    let blockchain = Blockchain::new(
        storage,
        vec![Box::new(EmptyService)],
        public_key,
        secret_key,
        app_tx.clone(),
    );
    let node = NodeContext::new(EXECUTOR.clone(), blockchain, public_key, app_tx);
    (node, app_rx)
}
