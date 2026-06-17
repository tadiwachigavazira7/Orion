// Inside src/features/consumer/consumerprofile/screens/consumerprofilescreen.tsx
import React, { useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { theme } from '../../../../theme';
import { ConsumerProfileSlider } from '../components/consumerprofileslider';
import { ProfileSliderPanel } from '../components/consumerprofilesliderpanel';

export default function ConsumerProfileScreen() {
  const [activeTab, setActiveTab] = useState('Edit Profile');
  
  

  const [firstName, setFirstName] = useState('James');
  const [lastName, setLastName] = useState('');

  return (
    <View style={styles.container}>
      <ConsumerProfileSlider 
        activeTab={activeTab} 
        onTabChange={setActiveTab} 
        firstName={firstName}
        lastName={lastName}
      />

      <View style={styles.contentBody}>
        {activeTab === 'Edit Profile' && (
          <ProfileSliderPanel 
            firstName={firstName}
            setFirstName={setFirstName}
            lastName={lastName}
            setLastName={setLastName}
          />
        )}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: theme.background,
  },
  contentBody: {
    flex: 1,
    marginTop: 20,
  },
});