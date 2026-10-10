//! Prints the brain's malli schemas for the proto types.

fn main() {
    print!("{}", gorilla_proto::malli_schema());
}
